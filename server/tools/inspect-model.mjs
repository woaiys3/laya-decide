/**
 * 检查 ONNX 模型的输入输出契约，并跑一次真实推理作为参考输出。
 *
 * 这个脚本是「参考基线」：Kotlin 侧跑出来的 logits 要和这里对齐。
 *
 *   node tools/inspect-model.mjs
 */

import * as ort from 'onnxruntime-node';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { Tokenizer } from '@huggingface/tokenizers';

import { EN_DIR as REF, requireModel, requireTokenizer } from './paths.mjs';

requireTokenizer();
const MODEL = requireModel();

const tokJson = JSON.parse(await readFile(path.join(REF, 'tokenizer.json'), 'utf8'));
const tokCfg = JSON.parse(await readFile(path.join(REF, 'tokenizer_config.json'), 'utf8'));
const tok = new Tokenizer(tokJson, tokCfg);

const session = await ort.InferenceSession.create(MODEL, { executionProviders: ['cpu'] });

console.log('=== 模型输入 ===');
for (const i of session.inputNames) console.log('  ' + i);
console.log('=== 模型输出 ===');
for (const o of session.outputNames) console.log('  ' + o);

// ---------------------------------------------------------------------------
// 复刻 build_sequence（来自 @receptron/laya dist/sequence.js）
// ---------------------------------------------------------------------------

const id = (t) => {
  const v = tok.token_to_id(t);
  if (v === undefined) throw new Error('缺少特殊 token ' + t);
  return v;
};
const IDS = { cls: id('[CLS]'), sep: id('[SEP]'), mask: id('[MASK]'), pad: id('[PAD]'), maskTok: '[MASK]' };
console.log('\n特殊 token id:', JSON.stringify(IDS));

const encode = (text) => tok.encode(text, { add_special_tokens: false }).ids;

function buildSequence(state, q, maxLen, headMaxLen) {
  const scrub = (s) => s.split(IDS.maskTok).join(' ');
  const opts =
    q.t === 'score'
      ? q.crit.map((c, i) => `level ${i}: ${c}`)
      : q.crit.map((c, i) => `level ${i}: ${c}`);

  let headIds = encode(`${q.t} question: ${scrub(q.ins)}`);
  let optIds = opts.map((o) => [IDS.mask, ...encode(' ' + scrub(o)).slice(0, 48)]);

  const total = (xs) => xs.reduce((s, o) => s + o.length, 0);
  let optBudget = headMaxLen - total(optIds);
  if (optBudget < 16) {
    const per = Math.max(4, Math.floor((headMaxLen - 16) / Math.max(1, optIds.length)));
    optIds = optIds.map((o) => o.slice(0, per));
    optBudget = headMaxLen - total(optIds);
  }
  headIds = headIds.slice(0, Math.max(8, optBudget));

  const seq = [IDS.cls, ...headIds, IDS.sep];
  const markers = [];
  for (const o of optIds) {
    markers.push(seq.length);
    seq.push(...o);
  }
  seq.push(IDS.sep);

  const room = Math.max(0, maxLen - seq.length - 1);
  const st = encode(scrub(typeof state === 'string' ? state : JSON.stringify(state))).slice(0, room);
  seq.push(...st, IDS.sep);

  return { ids: seq.slice(0, maxLen), markers: markers.filter((m) => m < maxLen) };
}

// ---------------------------------------------------------------------------
// 跑一个真实决策：score 题型，3 个选项
// ---------------------------------------------------------------------------

const CRITERIA = ['很不合适，弊大于利', '不太合适，勉强可以', '比较合适，值得考虑', '明显最佳，强烈推荐'];
const situation = '我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。';
const options = ['接受这个offer', '留在现在的大厂', '继续面其他公司'];

const cfg = JSON.parse(await readFile(path.join(REF, 'rl_agent_config.json'), 'utf8'));
const MAXLEN = cfg.max_len;
const HEADMAX = cfg.head_max_len;

console.log(`\nmax_len=${MAXLEN}  head_max_len=${HEADMAX}`);

const items = options.map((opt) => {
  const q = { t: 'score', ins: `在这个情境下，「${opt}」这个选择有多合适？请评估它对该情境的适配程度。`, crit: CRITERIA };
  const r = buildSequence(situation, q, MAXLEN, HEADMAX);
  return { opt, ...r };
});

// 组装 batch
const n = items.length;
const L = Math.max(...items.map((it) => it.ids.length));
const K = Math.max(...items.map((it) => it.markers.length));
const inputIds = new BigInt64Array(n * L).fill(BigInt(IDS.pad));
const attn = new BigInt64Array(n * L);
const mpos = new BigInt64Array(n * K);
const mmask = new Uint8Array(n * K);
const qtype = new BigInt64Array(n);

items.forEach((it, i) => {
  it.ids.forEach((v, j) => { inputIds[i * L + j] = BigInt(v); attn[i * L + j] = 1n; });
  it.markers.forEach((m, j) => { mpos[i * K + j] = BigInt(m); mmask[i * K + j] = 1; });
  qtype[i] = 1n; // score
});

console.log(`batch: n=${n} L=${L} K=${K}`);
console.log('各序列长度:', items.map((it) => it.ids.length).join(', '));
console.log('各 marker 位置:', items.map((it) => JSON.stringify(it.markers)).join(' '));

const out = await session.run({
  input_ids: new ort.Tensor('int64', inputIds, [n, L]),
  attention_mask: new ort.Tensor('int64', attn, [n, L]),
  marker_pos: new ort.Tensor('int64', mpos, [n, K]),
  marker_mask: new ort.Tensor('bool', mmask, [n, K]),
  qtype: new ort.Tensor('int64', qtype, [n]),
});

console.log('\n=== 输出张量 ===');
for (const [k, v] of Object.entries(out)) {
  console.log(`  ${k}: dims=${JSON.stringify(v.dims)} type=${v.type}`);
}

const logits = out['logits'].data;
console.log('\n=== 原始 logits ===');
for (let r = 0; r < n; r++) {
  const row = [];
  for (let k = 0; k < K; k++) row.push(logits[r * K + k].toFixed(4));
  console.log(`  ${items[r].opt.padEnd(18)} [${row.join(', ')}]`);
}

// 温度与 softmax
const sizeBucket = (k) => (k <= 2 ? '2' : k <= 5 ? '3-5' : k <= 10 ? '6-10' : '11+');
const temp = cfg.temperature_by_options[`score:${sizeBucket(K)}`] ?? cfg.temperature[1];
console.log(`\n温度 bucket=score:${sizeBucket(K)}  temp=${temp}`);

console.log('\n=== 校准后概率与期望分 ===');
for (let r = 0; r < n; r++) {
  const z = [];
  for (let k = 0; k < K; k++) z.push(logits[r * K + k] / temp);
  const zmax = Math.max(...z);
  const e = z.map((v) => Math.exp(v - zmax));
  const s = e.reduce((a, b) => a + b, 0);
  const p = e.map((v) => v / s);
  const expected = p.reduce((acc, v, i) => acc + i * v, 0);
  let ent = 0;
  for (const x of p) ent -= x * Math.log(Math.max(x, 1e-12));
  const conf = 1 - ent / Math.log(K);
  console.log(`  ${items[r].opt.padEnd(18)} score=${expected.toFixed(4)}  conf=${conf.toFixed(4)}  p=[${p.map((v) => v.toFixed(4)).join(', ')}]`);
}

await session.release();
