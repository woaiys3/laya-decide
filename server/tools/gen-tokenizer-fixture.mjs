/**
 * 用参考实现（@huggingface/tokenizers）生成 token id 对照夹具。
 *
 * 我随后要用 Kotlin 重写这个分词器。分词只要有一个 id 不对，模型的判断就可能变，
 * 所以必须逐 id 校验。这个脚本产出「输入文本 -> 期望 token id」，Kotlin 单测照着比。
 *
 *   node tools/gen-tokenizer-fixture.mjs
 *
 * 输出：app/app/src/test/resources/tokenizer-fixture.json
 */

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { Tokenizer } from '@huggingface/tokenizers';

const REF_DIR = 'D:/laya/reference/en';
const OUT = 'D:/laya/app/app/src/test/resources/tokenizer-fixture.json';

const tokJson = JSON.parse(await readFile(path.join(REF_DIR, 'tokenizer.json'), 'utf8'));
const tokCfg = JSON.parse(await readFile(path.join(REF_DIR, 'tokenizer_config.json'), 'utf8'));
const tok = new Tokenizer(tokJson, tokCfg);

const encode = (text) => tok.encode(text, { add_special_tokens: false }).ids;

/**
 * 测试用例刻意覆盖 App 会真实产生的文本形态，
 * 而不是随便找些句子 —— 这样夹具才能真正保护到线上路径。
 */
const cases = [
  // --- build_sequence 的头部：`${qtype} question: ${instructions}` ---
  'score question: 在这个情境下，「接受这个 offer」这个选择有多合适？请评估它对该情境的适配程度。',
  'score question: 在这个情境下，「留在现在的大厂」这个选择有多合适？请评估它对该情境的适配程度。',
  'choice question: Which team should handle this ticket?',
  'noul question: Does the user threaten to cancel?',

  // --- 档位文本（render_options 的 score 分支会加 "level i: " 前缀）---
  ' level 0: 很不合适，弊大于利',
  ' level 1: 不太合适，勉强可以',
  ' level 2: 比较合适，值得考虑',
  ' level 3: 明显最佳，强烈推荐',

  // --- 选项文本（前面会加一个空格）---
  ' 接受这个offer',
  ' 留在现在的大厂',
  ' 继续面其他公司',
  ' 本市周边爬山',
  ' 去隔壁城市逛吃',
  ' 在家躺平看电影',
  ' 露营野餐',
  ' 逛博物馆',

  // --- state ---
  '我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。',
  '周末想出去玩，预算500，两个人，不想开车太远，想放松一下。',
  '同事推荐我买某只基金，说稳赚不赔，我手里有10万闲钱，但明年要用。',

  // --- 边界：前导/尾随空格、连续空格、换行、制表符 ---
  '   ',
  ' ',
  '',
  '  leading and trailing  ',
  'a\nb',
  'a\tb',
  'a  b',
  '\n\n',
  'multiple    spaces    between',

  // --- 标点与符号 ---
  'Hello, world! How are you?',
  "don't can't I'm we're they'll",
  '1+1=2, 3.14, 100%, $5, 2024-01-01',
  'email@example.com https://example.com/path?q=1',
  '（）【】「」《》、。，！？；：',
  '— – ‐ - ―',
  '"quoted" \'single\' `backtick`',

  // --- 数字 ---
  '1234567890',
  '0.5',
  '-42',
  '1e10',

  // --- 中文细分 ---
  '决策',
  '一个',
  '这是一个测试句子，用来检验中文分词的边界情况。',
  '繁体字：測試',
  '中英混排 mixed 内容 with English 夹杂',

  // --- 特殊 token 字面量（保证它们被识别为单个 id，而不是被切碎）---
  '[CLS]',
  '[SEP]',
  '[MASK]',
  '[PAD]',
  '[UNK]',
  'a [MASK] b',
  '[CLS] score question: test [SEP]',

  // --- 表情与少见字符 ---
  '😀🎉👍',
  'emoji 😀 in text',
  'café naïve résumé',
  'ｆｕｌｌｗｉｄｔｈ　ＡＢＣ',
  '① ② ③ № ½',
  'Ω≈ç√∫˜µ≤≥÷',

  // --- 长文本 ---
  '这是一个很长的句子，'.repeat(30),
  'The quick brown fox jumps over the lazy dog. '.repeat(10),

  // --- 空白变体 ---
  '\u00A0non-breaking\u00A0space',
  '\u200Bzero-width',
  '　全角空格　',

  // --- 超长单词 ---
  'a'.repeat(300),
  '中'.repeat(200),
];

const fixtures = [];
const seen = new Set();

for (const text of cases) {
  if (seen.has(text)) continue;
  seen.add(text);
  try {
    fixtures.push({ text, ids: encode(text) });
  } catch (e) {
    console.log(`  跳过（参考实现报错）: ${JSON.stringify(text).slice(0, 40)} -> ${e.message}`);
  }
}

await mkdir(path.dirname(OUT), { recursive: true });
await writeFile(OUT, JSON.stringify({ fixtures }, null, 1), 'utf8');

console.log(`生成 ${fixtures.length} 条夹具 -> ${OUT}`);
console.log('');
console.log('抽查：');
for (const t of ['决策', ' 接受这个offer', '[MASK]', '😀🎉👍', '   ']) {
  const f = fixtures.find((x) => x.text === t);
  if (f) console.log(`  ${JSON.stringify(t).padEnd(24)} -> [${f.ids.join(', ')}]`);
}
