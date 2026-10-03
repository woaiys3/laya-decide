/**
 * 交叉验证：用「Kotlin 生成的序列」喂给「真实的 int4 模型」，
 * 确认序列是被模型接受的，并顺便记录期望的 logits。
 *
 * 为什么需要这一步：分词和序列构造都通过了逐 id 夹具，但那只证明
 * "和参考实现的 JS 一致"。这一步再证一次"和真实模型跑得通"。
 *
 *   node tools/cross-check.mjs
 *
 * 输入：Kotlin 侧 dump 出来的序列。先跑这个生成它：
 *   gradlew :app:testDebugUnitTest --tests "*SequenceDumpTest*"
 */

import * as ort from 'onnxruntime-node';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { EN_DIR, repoRoot, requireModel, requireTokenizer } from './paths.mjs';

requireTokenizer();

const MODEL = requireModel();
const CFG = JSON.parse(await readFile(path.join(EN_DIR, 'rl_agent_config.json'), 'utf8'));
const DUMP_FILE = path.join(repoRoot, 'app/app/build/kotlin-sequence-dump.json');
const DUMP = JSON.parse(await readFile(DUMP_FILE, 'utf8'));

console.log(`读到 ${DUMP.cases.length} 个来自 Kotlin 的序列`);
console.log(`maxLen=${CFG.max_len} head_max_len=${CFG.head_max_len}`);

const session = await ort.InferenceSession.create(MODEL, { executionProviders: ['cpu'] });

const sizeBucket = (k) => (k <= 2 ? '2' : k <= 5 ? '3-5' : k <= 10 ? '6-10' : '11+');

for (const c of DUMP.cases) {
  const n = c.sequences.length;
  const K = c.sequences[0].markers.length;
  const L = Math.max(...c.sequences.map((s) => s.ids.length));

  console.log(`\n--- ${c.name} ---`);
  console.log(`  n=${n} L=${L} K=${K}`);

  const inputIds = new BigInt64Array(n * L).fill(BigInt(DUMP.padId));
  const attn = new BigInt64Array(n * L);
  const mpos = new BigInt64Array(n * K);
  const mmask = new Uint8Array(n * K);
  const qtype = new BigInt64Array(n);

  c.sequences.forEach((s, i) => {
    s.ids.forEach((v, j) => {
      inputIds[i * L + j] = BigInt(v);
      attn[i * L + j] = 1n;
    });
    s.markers.forEach((m, j) => {
      mpos[i * K + j] = BigInt(m);
      mmask[i * K + j] = 1;
    });
    qtype[i] = BigInt(c.qtypeIndex ?? 1);
  });

  let out;
  try {
    out = await session.run({
      input_ids: new ort.Tensor('int64', inputIds, [n, L]),
      attention_mask: new ort.Tensor('int64', attn, [n, L]),
      marker_pos: new ort.Tensor('int64', mpos, [n, K]),
      marker_mask: new ort.Tensor('bool', mmask, [n, K]),
      qtype: new ort.Tensor('int64', qtype, [n]),
    });
  } catch (e) {
    console.log('  x 模型拒绝了这组输入: ' + e.message);
    continue;
  }

  const logits = out['logits'].data;
  const temp = CFG.temperature_by_options[`score:${sizeBucket(K)}`] ?? CFG.temperature[1];

  console.log(`  模型接受输入 OK   温度=${temp.toFixed(4)}`);

  const results = [];
  for (let r = 0; r < n; r++) {
    const raw = [];
    for (let k = 0; k < K; k++) raw.push(logits[r * K + k]);

    const z = raw.map((v) => v / temp);
    const zmax = Math.max(...z);
    const e = z.map((v) => Math.exp(v - zmax));
    const sum = e.reduce((a, b) => a + b, 0);
    const p = e.map((v) => v / sum);
    const expected = p.reduce((acc, v, i) => acc + i * v, 0);
    let ent = 0;
    for (const x of p) ent -= x * Math.log(Math.max(x, 1e-12));
    const conf = 1 - ent / Math.log(K);

    results.push({ option: c.options[r], expected, conf, raw });
  }

  results.sort((a, b) => b.expected - a.expected);
  for (const r of results) {
    console.log(
      `    ${r.option.padEnd(20)} score=${r.expected.toFixed(4)} conf=${r.conf.toFixed(4)}` +
        `  raw=[${r.raw.map((v) => v.toFixed(3)).join(', ')}]`,
    );
  }
  console.log(`  => 推荐: ${results[0].option}`);
}

await session.release();
