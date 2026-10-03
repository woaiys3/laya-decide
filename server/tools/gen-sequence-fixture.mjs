/**
 * 用参考实现生成「序列构造」夹具：给定情境+选项，期望的 input_ids 和 marker 位置。
 *
 * Kotlin 的 SequenceBuilder 必须逐 id 复现它，否则模型看到的输入就变了。
 *
 *   node tools/gen-sequence-fixture.mjs
 */

import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Tokenizer } from '@huggingface/tokenizers';

import { EN_DIR as REF, repoRoot, requireTokenizer } from './paths.mjs';

requireTokenizer();
const OUT = path.join(repoRoot, 'app/app/src/test/resources/sequence-fixture.json');

const tokJson = JSON.parse(await readFile(path.join(REF, 'tokenizer.json'), 'utf8'));
const tokCfg = JSON.parse(await readFile(path.join(REF, 'tokenizer_config.json'), 'utf8'));
const tok = new Tokenizer(tokJson, tokCfg);

const id = (t) => {
  const v = tok.token_to_id(t);
  if (v === undefined) throw new Error('缺少特殊 token ' + t);
  return v;
};
const IDS = { cls: id('[CLS]'), sep: id('[SEP]'), mask: id('[MASK]'), pad: id('[PAD]'), maskTok: '[MASK]' };
const encode = (text) => tok.encode(text, { add_special_tokens: false }).ids;

// --- 复刻 dist/sequence.js 的 buildSequence（与 rl_common.build_sequence 等价） ---
function buildSequence(state, q, maxLen, headMaxLen) {
  const scrub = (s) => s.split(IDS.maskTok).join(' ');
  const opts =
    q.t === 'choice'
      ? Object.entries(q.crit).map(([k, v]) => (v ? `${k}: ${v}` : k))
      : q.t === 'score'
        ? q.crit.map((c, i) => `level ${i}: ${c}`)
        : ['false: ' + (q.crit?.false || 'no, the statement does not hold'),
           'true: ' + (q.crit?.true || 'yes, the statement holds')];

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

const cfg = JSON.parse(await readFile(path.join(REF, 'rl_agent_config.json'), 'utf8'));
const MAXLEN = cfg.max_len;
const HEADMAX = cfg.head_max_len;

const CRITERIA = ['很不合适，弊大于利', '不太合适，勉强可以', '比较合适，值得考虑', '明显最佳，强烈推荐'];
const CRITERIA_5 = ['很不合适', '不太合适', '一般', '比较合适', '明显最佳'];

/**
 * ⚠️ 必须与 Kotlin 的 LayaAgent.scoreOptions 里的措辞逐字一致。
 *
 * 措辞是实测挑出来的（server/tools/prompt-experiment.mjs）：
 * 直白的「有多好？」比原来的「适配程度」区分度高。
 * 改任何一处都要同步改另一处，并重新生成夹具。
 */
function scoreQ(opt, criteria) {
  return {
    t: 'score',
    ins: `面对上述情境，选择「${opt}」有多好？`,
    crit: criteria,
  };
}

const cases = [];

// --- 1. 基线：3 个选项，中文 ---
{
  const situation = '我拿到一个创业公司offer，薪资降30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。';
  const options = ['接受这个offer', '留在现在的大厂', '继续面其他公司'];
  const items = options.map((o) => ({ option: o, ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '中文3选项', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 2. 5 个选项 ---
{
  const situation = '周末想出去玩，预算500，两个人，不想开车太远，想放松一下。';
  const options = ['本市周边爬山', '去隔壁城市逛吃', '在家躺平看电影', '露营野餐', '逛博物馆'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '中文5选项', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 3. 英文 ---
{
  const situation = 'I got an offer from a startup: 30% pay cut but equity and a real say in the product. I currently coast at a big company and have a mortgage.';
  const options = ['Take the offer', 'Stay at the big company', 'Keep interviewing'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA_5), MAXLEN, HEADMAX) }));
  cases.push({ name: '英文3选项', situation, options, criteria: CRITERIA_5, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 4. 超长情境：验证 max_len 截断 ---
{
  const situation = '这是一个很长的情境描述，'.repeat(80);
  const options = ['选项甲', '选项乙'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '超长情境截断', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 5. 超长选项：验证 option 文本被截到 48 token ---
{
  const situation = '短情境。';
  const options = ['这是一个非常长的选项文本'.repeat(20), '短选项'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '超长选项截断', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 6. 很多选项：验证 head 预算收缩分支（opt_budget < 16）---
{
  const situation = '选一个。';
  const options = Array.from({ length: 20 }, (_, i) => `很长的候选方案编号${i}需要占掉很多token空间`);
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '20选项预算收缩', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 7. 情境含 [MASK] 字面量：验证 scrub ---
{
  const situation = '用户输入里带了 [MASK] 这样的字面量，还有 [CLS] 和 [SEP]。';
  const options = ['选项里有[MASK]标记', '干净选项'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA), MAXLEN, HEADMAX) }));
  cases.push({ name: '情境含特殊token字面量', situation, options, criteria: CRITERIA, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

// --- 8. 4 档之外：5 档 criteria ---
{
  const situation = '评估这个方案。';
  const options = ['方案一', '方案二', '方案三'];
  const items = options.map((o) => ({ ...buildSequence(situation, scoreQ(o, CRITERIA_5), MAXLEN, HEADMAX) }));
  cases.push({ name: '5档评分', situation, options, criteria: CRITERIA_5, markersPerOption: items.map((i) => i.markers), inputIds: items.map((i) => i.ids) });
}

const fixture = {
  config: { maxLen: MAXLEN, headMaxLen: HEADMAX },
  specialIds: IDS,
  cases,
};

await writeFile(OUT, JSON.stringify(fixture, null, 1), 'utf8');

console.log(`生成 ${cases.length} 条序列夹具 -> ${OUT}`);
console.log('');
for (const c of cases) {
  console.log(`  ${c.name}`);
  console.log(`    序列长度: ${c.inputIds.map((x) => x.length).join(', ')}   markers: ${JSON.stringify(c.markersPerOption)}`);
}
