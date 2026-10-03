/**
 * 提示词实验：在真实模型上比较几种提问方式，找出区分度最好的那个。
 *
 * 背景：用 4 档评分时，模型对开放式选择经常给出接近均匀的分布
 * （期望分全挤在 1.5 附近，置信度 < 0.25），前几名分差 0.02。
 * 结果就是"看起来给了分，其实等于没选"。
 *
 * 这个脚本量化比较几种提问方式，用数据决定改不改，而不是凭感觉。
 *
 *   node tools/prompt-experiment.mjs
 */

import * as ort from 'onnxruntime-node';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { Tokenizer } from '@huggingface/tokenizers';

const REF = 'D:/laya/reference/en';
const MODEL = 'D:/laya/reference/models/model_int4.onnx';

const tokJson = JSON.parse(await readFile(path.join(REF, 'tokenizer.json'), 'utf8'));
const tokCfg = JSON.parse(await readFile(path.join(REF, 'tokenizer_config.json'), 'utf8'));
const cfg = JSON.parse(await readFile(path.join(REF, 'rl_agent_config.json'), 'utf8'));
const tok = new Tokenizer(tokJson, tokCfg);

const sid = (t) => tok.token_to_id(t);
const IDS = { cls: sid('[CLS]'), sep: sid('[SEP]'), mask: sid('[MASK]'), pad: sid('[PAD]'), maskTok: '[MASK]' };
const encode = (t) => tok.encode(t, { add_special_tokens: false }).ids;

function buildSequence(state, q, maxLen, headMaxLen) {
  const scrub = (s) => s.split(IDS.maskTok).join(' ');
  const opts = q.crit.map((c, i) => `level ${i}: ${c}`);
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
  for (const o of optIds) { markers.push(seq.length); seq.push(...o); }
  seq.push(IDS.sep);
  const room = Math.max(0, maxLen - seq.length - 1);
  const st = encode(scrub(state)).slice(0, room);
  seq.push(...st, IDS.sep);
  return { ids: seq.slice(0, maxLen), markers: markers.filter((m) => m < maxLen) };
}

const session = await ort.InferenceSession.create(MODEL, { executionProviders: ['cpu'] });

// ---------------------------------------------------------------------------

async function scoreAll(situation, options, criteria, insFn) {
  const items = options.map((opt) => buildSequence(situation, { t: 'score', ins: insFn(opt), crit: criteria }, cfg.max_len, cfg.head_max_len));
  const n = items.length, K = criteria.length;
  const L = Math.max(...items.map((i) => i.ids.length));

  const ids = new BigInt64Array(n * L).fill(BigInt(IDS.pad));
  const att = new BigInt64Array(n * L);
  const mp = new BigInt64Array(n * K);
  const mm = new Uint8Array(n * K);
  const qt = new BigInt64Array(n).fill(1n);

  items.forEach((it, i) => {
    it.ids.forEach((v, j) => { ids[i * L + j] = BigInt(v); att[i * L + j] = 1n; });
    it.markers.forEach((m, j) => { mp[i * K + j] = BigInt(m); mm[i * K + j] = 1; });
  });

  const out = await session.run({
    input_ids: new ort.Tensor('int64', ids, [n, L]),
    attention_mask: new ort.Tensor('int64', att, [n, L]),
    marker_pos: new ort.Tensor('int64', mp, [n, K]),
    marker_mask: new ort.Tensor('bool', mm, [n, K]),
    qtype: new ort.Tensor('int64', qt, [n]),
  });

  const logits = out['logits'].data;
  const sizeBucket = (k) => (k <= 2 ? '2' : k <= 5 ? '3-5' : k <= 10 ? '6-10' : '11+');
  const temp = cfg.temperature_by_options[`score:${sizeBucket(K)}`] ?? cfg.temperature[1];

  return options.map((opt, r) => {
    const z = [];
    for (let k = 0; k < K; k++) z.push(logits[r * K + k] / temp);
    const zmax = Math.max(...z);
    const e = z.map((v) => Math.exp(v - zmax));
    const s = e.reduce((a, b) => a + b, 0);
    const p = e.map((v) => v / s);
    const expected = p.reduce((acc, v, i) => acc + i * v, 0);
    let ent = 0;
    for (const x of p) ent -= x * Math.log(Math.max(x, 1e-12));
    return { opt, expected, conf: 1 - ent / Math.log(K), p };
  });
}

// ---------------------------------------------------------------------------
// 变体定义
// ---------------------------------------------------------------------------

const CRIT_4 = ['很不合适，弊大于利', '不太合适，勉强可以', '比较合适，值得考虑', '明显最佳，强烈推荐'];
const CRIT_5 = ['很差的选择', '偏差的选择', '一般', '好的选择', '最好的选择'];
const CRIT_3 = ['不合适', '可以接受', '最合适'];

const variants = [
  {
    name: 'A 现状：4档+适配度提问',
    criteria: CRIT_4,
    ins: (o) => `在这个情境下，「${o}」这个选择有多合适？请评估它对该情境的适配程度。`,
  },
  {
    name: 'B 直白：4档+哪个更好',
    criteria: CRIT_4,
    ins: (o) => `面对上述情境，选择「${o}」有多好？`,
  },
  {
    name: 'C 3档+直白',
    criteria: CRIT_3,
    ins: (o) => `面对上述情境，选择「${o}」有多好？`,
  },
  {
    name: 'D 5档+偏好措辞',
    criteria: CRIT_5,
    ins: (o) => `如果只能从这些选项里选一个，「${o}」是不是最佳选择？`,
  },
  {
    name: 'E 3档+最佳选择',
    criteria: CRIT_3,
    ins: (o) => `哪一个选项最符合这个人的利益？「${o}」符合程度如何？`,
  },
];

const scenarios = [
  {
    situation: '周末想出去玩，预算500，两个人，不想开车太远，想放松一下。',
    options: ['本市周边爬山', '去隔壁城市逛吃', '在家躺平看电影', '露营野餐', '逛博物馆'],
  },
  {
    situation: '我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。',
    options: ['接受这个offer', '留在现在的大厂', '继续面其他公司'],
  },
  {
    situation: '同事推荐我买某只基金，说稳赚不赔，我手里有10万闲钱，但明年要用。',
    options: ['全买进去', '先买一半', '不买，放货币基金', '再研究研究'],
  },
  {
    situation: '我想学一门新技能，每天能挤出一小时，目的是三年后换工作涨薪。',
    options: ['学英语', '学编程', '考一个专业证书', '学视频剪辑'],
  },
];

// ---------------------------------------------------------------------------

console.log('提示词实验：区分度对比\n');
console.log('指标说明：');
console.log('  spread   = 最高分 - 最低分（跨选项区分度，越大越能分出高下）');
console.log('  margin   = 第一名 - 第二名（决策是否有意义，越大越果断）');
console.log('  avgConf  = 平均置信度（模型有多确定，越大越好）');
console.log('');

const summary = [];

for (const v of variants) {
  let allSpread = [], allMargin = [], allConf = [];

  for (const sc of scenarios) {
    const res = await scoreAll(sc.situation, sc.options, v.criteria, v.ins);
    res.sort((a, b) => b.expected - a.expected);
    const spread = res[0].expected - res[res.length - 1].expected;
    const margin = res[0].expected - res[1].expected;
    const avgConf = res.reduce((a, r) => a + r.conf, 0) / res.length;
    allSpread.push(spread);
    allMargin.push(margin);
    allConf.push(avgConf);
  }

  const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
  summary.push({
    name: v.name,
    spread: mean(allSpread),
    margin: mean(allMargin),
    conf: mean(allConf),
    rawSpread: allSpread,
    rawMargin: allMargin,
    rawConf: allConf,
  });
}

const pad = (s, n) => String(s).padEnd(n);
console.log(pad('变体', 30) + pad('spread', 10) + pad('margin', 10) + 'avgConf');
console.log('-'.repeat(62));
for (const s of summary) {
  console.log(
    pad(s.name, 30) +
      pad(s.spread.toFixed(3), 10) +
      pad(s.margin.toFixed(3), 10) +
      s.conf.toFixed(3),
  );
}

// 逐场景明细，看清"平均好"是不是被某个场景拉高的
console.log('\n逐场景 margin（第一名与第二名的分差）：');
for (const s of summary) {
  console.log('  ' + pad(s.name, 30) + s.rawMargin.map((x) => x.toFixed(3)).join('  '));
}

console.log('\n逐场景 avgConf：');
for (const s of summary) {
  console.log('  ' + pad(s.name, 30) + s.rawConf.map((x) => x.toFixed(3)).join('  '));
}

await session.release();
