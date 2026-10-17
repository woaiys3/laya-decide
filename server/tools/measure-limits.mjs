/**
 * 量一下「背景」和「选项」实际能塞进模型多少字。
 *
 * 背景：有用户反馈"背景和选项能写的字太少"。但模型的上下文是硬约束
 * （max_len=512），超过就被**静默截断**。所以先量数字，而不是直接调大 UI 上限。
 *
 *   node tools/measure-limits.mjs
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { Tokenizer } from '@huggingface/tokenizers';
import { EN_DIR, requireTokenizer } from './paths.mjs';

requireTokenizer();

const tok = new Tokenizer(
  JSON.parse(await readFile(path.join(EN_DIR, 'tokenizer.json'), 'utf8')),
  JSON.parse(await readFile(path.join(EN_DIR, 'tokenizer_config.json'), 'utf8')),
);
const cfg = JSON.parse(await readFile(path.join(EN_DIR, 'rl_agent_config.json'), 'utf8'));

const enc = (t) => tok.encode(t, { add_special_tokens: false }).ids;

// 与 App 一致的 4 档
const CRIT = ['很不合适，弊大于利', '不太合适，勉强可以', '比较合适，值得考虑', '明显最佳，强烈推荐'];

/** 复刻 build_sequence，只算长度，不真的建序列。 */
function budget(option) {
  const ins = `面对上述情境，选择「${option}」有多好？`;
  let head = enc('score question: ' + ins);
  const opts = CRIT.map((c, i) => [tok.token_to_id('[MASK]'), ...enc(` level ${i}: ${c}`).slice(0, 48)]);

  const total = opts.reduce((s, o) => s + o.length, 0);
  let optBudget = cfg.head_max_len - total;
  if (optBudget < 16) {
    const per = Math.max(4, Math.floor((cfg.head_max_len - 16) / opts.length));
    optBudget = cfg.head_max_len - opts.map((o) => o.slice(0, per)).reduce((s, o) => s + o.length, 0);
  }
  head = head.slice(0, Math.max(8, optBudget));

  const seqLen = 1 + head.length + 1 + total + 1; // [CLS] head [SEP] opts [SEP]
  const room = Math.max(0, cfg.max_len - seqLen - 1); // 再留 1 个 [SEP]
  return { room, seqLen, headLen: head.length, optLen: total, headBudget: Math.max(8, optBudget) };
}

console.log('=== 模型配置 ===');
console.log(`  max_len      = ${cfg.max_len}`);
console.log(`  head_max_len = ${cfg.head_max_len}`);
console.log('');

const b = budget('接受这个offer');
console.log('=== 固定开销（与背景长度无关）===');
console.log(
  `  [CLS] 1 + 问题头 ${b.headLen} + [SEP] 1 + 4个档位 ${b.optLen} + [SEP] 1 = ${b.seqLen} tokens`,
);
console.log(`  → 留给「背景」的只有 ${b.room} tokens`);
console.log('');

// 中文 token 密度
const samples = [
  '我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。',
  '我今年32岁，在一家互联网公司做了6年运营，月薪2万，有房贷每月8000。最近收到一个创业公司的offer，薪资降30%但要负责整条业务线。我担心风险，但也不想一直原地踏步。',
];
console.log('=== 中文 token 密度（实测）===');
let charsPerToken = 1;
for (const s of samples) {
  const n = enc(s).length;
  charsPerToken = s.length / n;
  console.log(`  ${String(s.length).padStart(4)} 字 → ${String(n).padStart(4)} tokens   （1 token ≈ ${charsPerToken.toFixed(2)} 字）`);
}
console.log('');

const maxChars = Math.floor(b.room * charsPerToken);
console.log('=== 结论：背景 ===');
console.log(`  实际能进模型   : 约 ${b.room} tokens ≈ ${maxChars} 个中文字`);
console.log(`  当前 UI 上限   : 1200 字`);
if (1200 > maxChars) {
  const dropped = 1200 - maxChars;
  console.log(`  ⚠️ 超出部分被静默丢弃: 约 ${dropped} 字（${Math.round((dropped / 1200) * 100)}%）`);
}
console.log('');

console.log('=== 结论：选项 ===');
console.log(
  `  选项文字进「问题头」，其预算 = head_max_len(${cfg.head_max_len}) - 档位(${b.optLen}) = ${b.headBudget} tokens`,
);
for (const n of [20, 40, 60, 80, 85, 100, 120]) {
  const need = enc(`score question: 面对上述情境，选择「${'选'.repeat(n)}」有多好？`).length;
  const ok = need <= b.headBudget;
  console.log(
    `  ${String(n).padStart(3)} 字选项 → 问题头需 ${String(need).padStart(3)} tokens  ${ok ? '✅ 装得下' : '❌ 会被截断'}`,
  );
}
console.log(`  当前 UI 上限   : 60 字  → 还算保守，理论上可放到约 85 字`);
console.log('');

// 如果换成 1024 上下文的 checkpoint
const b1024 = budget('接受这个offer');
const room1024 = cfg.max_len === 512 ? 1024 - b1024.seqLen - 1 : null;
console.log('=== 假如换成 1024 上下文的 checkpoint ===');
console.log(`  仍要减去同样 ${b1024.seqLen} tokens 的固定开销`);
console.log(`  → 背景可到 ${room1024} tokens ≈ ${Math.floor(room1024 * charsPerToken)} 字（约 ${(room1024 / b.room).toFixed(1)} 倍）`);
