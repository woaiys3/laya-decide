/**
 * 单测 —— 验证决策核心逻辑。用 Node 内置 test runner，无额外依赖。
 *
 *   cd D:\laya\server
 *   node --test test/
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  SCORE_CRITERIA,
  MAX_SCORE,
  buildQuestions,
  rankOptions,
  assembleDecision,
  confidenceFromDistribution,
  validateInput,
  cleanOptions,
  scoreKey,
} from '../laya-core.mjs';

// ---------------------------------------------------------------------------
// buildQuestions
// ---------------------------------------------------------------------------

test('buildQuestions 为每个选项生成一个 score 问题', () => {
  const options = ['留下', '接受 offer', '继续面试'];
  const q = buildQuestions(options);

  assert.equal(Object.keys(q).length, 3);
  for (let i = 0; i < options.length; i++) {
    const item = q[scoreKey(i)];
    assert.equal(item.type, 'score');
    assert.deepEqual(item.criteria, SCORE_CRITERIA);
    assert.ok(item.instructions.includes(options[i]), '题目里要包含选项原文');
  }
});

test('buildQuestions 把选项写进 instructions 而不是 criteria', () => {
  // 这是本项目的核心设计决定：criteria 是档位（固定），选项进 instructions。
  // 如果哪天有人改回 criteria 放选项，这个测试会失败并提醒他去看 PROJECT.md。
  const q = buildQuestions(['A', 'B']);
  assert.deepEqual(q[scoreKey(0)].criteria, SCORE_CRITERIA);
  assert.ok(!Object.keys(q[scoreKey(0)].criteria).includes('A'));
});

// ---------------------------------------------------------------------------
// rankOptions
// ---------------------------------------------------------------------------

test('rankOptions 按分数降序排列', () => {
  const options = ['低', '高', '中'];
  const answers = {
    [scoreKey(0)]: { score: 0.5, distribution: [0.5, 0.5, 0, 0] },
    [scoreKey(1)]: { score: 2.9, distribution: [0, 0, 0.1, 0.9] },
    [scoreKey(2)]: { score: 1.5, distribution: [0.2, 0.3, 0.4, 0.1] },
  };

  const ranked = rankOptions(options, answers);
  assert.deepEqual(ranked.map((r) => r.option), ['高', '中', '低']);
  assert.equal(ranked[0].normalized, 2.9 / MAX_SCORE);
});

test('rankOptions 同分时保持输入顺序（稳定排序）', () => {
  const options = ['第一', '第二', '第三'];
  const flat = { score: 2.0, distribution: [0, 0, 1, 0] };
  const answers = { [scoreKey(0)]: flat, [scoreKey(1)]: flat, [scoreKey(2)]: flat };

  const ranked = rankOptions(options, answers);
  assert.deepEqual(ranked.map((r) => r.option), ['第一', '第二', '第三']);
});

test('rankOptions 对缺失或非法分数不崩，按 0 处理', () => {
  const options = ['有分', '缺分', 'NaN'];
  const answers = {
    [scoreKey(0)]: { score: 1.0, distribution: [0, 0, 1, 0] },
    // scoreKey(1) 缺失
    [scoreKey(2)]: { score: Number.NaN, distribution: null },
  };

  const ranked = rankOptions(options, answers);
  assert.equal(ranked.length, 3);
  assert.equal(ranked[0].option, '有分');
  assert.equal(ranked.find((r) => r.option === '缺分').score, 0);
  assert.equal(ranked.find((r) => r.option === 'NaN').score, 0);
});

test('rankOptions 把超出档位范围的分数夹回来', () => {
  const ranked = rankOptions(['爆表', '负值'], {
    [scoreKey(0)]: { score: 99, distribution: [0, 0, 0, 1] },
    [scoreKey(1)]: { score: -5, distribution: [1, 0, 0, 0] },
  });
  assert.equal(ranked[0].score, MAX_SCORE);
  assert.equal(ranked[1].score, 0);
});

// ---------------------------------------------------------------------------
// confidenceFromDistribution
// ---------------------------------------------------------------------------

test('分布越集中，置信度越高', () => {
  const sharp = confidenceFromDistribution([0, 0, 0, 1]);
  const flat = confidenceFromDistribution([0.25, 0.25, 0.25, 0.25]);
  assert.equal(sharp, 1);
  assert.equal(flat, 0);
});

test('置信度落在 0..1 之间', () => {
  for (const d of [[0.1, 0.2, 0.3, 0.4], [0.5, 0.5], [1], [0, 0, 0, 0]]) {
    const c = confidenceFromDistribution(d);
    assert.ok(c >= 0 && c <= 1, `分布 ${JSON.stringify(d)} 得到 ${c}`);
  }
});

test('空分布或缺失分布返回 0', () => {
  assert.equal(confidenceFromDistribution(undefined), 0);
  assert.equal(confidenceFromDistribution([]), 0);
});

// ---------------------------------------------------------------------------
// assembleDecision
// ---------------------------------------------------------------------------

test('assembleDecision 标出胶着局面', () => {
  const d = assembleDecision(['甲', '乙'], {
    [scoreKey(0)]: { score: 2.80, distribution: [0, 0, 0.2, 0.8] },
    [scoreKey(1)]: { score: 2.78, distribution: [0, 0, 0.22, 0.78] },
  });

  assert.equal(d.pick, '甲');
  assert.equal(d.closeCall, true);
  assert.equal(d.marginLabel, '胶着');
  assert.ok(d.note.includes('甲') && d.note.includes('乙'));
});

test('assembleDecision 标出明确倾向', () => {
  const d = assembleDecision(['甲', '乙'], {
    [scoreKey(0)]: { score: 3.0, distribution: [0, 0, 0, 1] },
    [scoreKey(1)]: { score: 0.2, distribution: [0.8, 0.2, 0, 0] },
  });

  assert.equal(d.marginLabel, '明确');
  assert.equal(d.closeCall, false);
});

test('assembleDecision 处理单选项', () => {
  const d = assembleDecision(['唯一'], {
    [scoreKey(0)]: { score: 1.5, distribution: [0.1, 0.3, 0.5, 0.1] },
  });
  assert.equal(d.pick, '唯一');
  assert.equal(d.margin, null);
  assert.equal(d.marginLabel, 'single');
});

test('assembleDecision 处理空选项不崩', () => {
  const d = assembleDecision([], {});
  assert.equal(d.pick, null);
  assert.deepEqual(d.ranked, []);
});

// ---------------------------------------------------------------------------
// validateInput
// ---------------------------------------------------------------------------

test('validateInput 拒绝空情境', () => {
  assert.equal(validateInput('', ['a', 'b']).ok, false);
  assert.equal(validateInput('   ', ['a', 'b']).ok, false);
  assert.equal(validateInput(null, ['a', 'b']).ok, false);
});

test('validateInput 拒绝过短和过长的选项列表', () => {
  assert.equal(validateInput('情境', ['只有一个']).ok, false);
  assert.equal(validateInput('情境', Array.from({ length: 21 }, (_, i) => `选项${i}`)).ok, false);
});

test('validateInput 拒绝重复选项', () => {
  const r = validateInput('情境', ['一样', '一样']);
  assert.equal(r.ok, false);
  assert.ok(r.error.includes('重复'));
});

test('validateInput 拒绝过长的情境和选项', () => {
  assert.equal(validateInput('字'.repeat(2000), ['a', 'b']).ok, false);
  assert.equal(validateInput('情境', ['a', '很长的选项'.repeat(20)]).ok, false);
});

test('validateInput 接受正常输入', () => {
  assert.equal(validateInput('该不该接受这个 offer', ['接受', '拒绝']).ok, true);
});

// ---------------------------------------------------------------------------
// cleanOptions
// ---------------------------------------------------------------------------

test('cleanOptions 去空白、去空项、去重且保序', () => {
  assert.deepEqual(cleanOptions(['  b  ', '', 'a', 'b', '   ', 'c']), ['b', 'a', 'c']);
});

test('cleanOptions 忽略非字符串', () => {
  assert.deepEqual(cleanOptions(['a', 42, null, undefined, {}, 'b']), ['a', 'b']);
});

// ---------------------------------------------------------------------------
// 端到端：从选项到决策
// ---------------------------------------------------------------------------

test('端到端：问题集与排名能对上号', () => {
  const options = ['跳槽', '留下', '读研'];
  const q = buildQuestions(options);
  assert.equal(Object.keys(q).length, options.length);

  // 模拟模型返回：跳槽最高
  const answers = {
    [scoreKey(0)]: { score: 2.7, distribution: [0, 0.05, 0.2, 0.75] },
    [scoreKey(1)]: { score: 1.1, distribution: [0.2, 0.5, 0.3, 0] },
    [scoreKey(2)]: { score: 0.4, distribution: [0.7, 0.2, 0.1, 0] },
  };

  const d = assembleDecision(options, answers);
  assert.equal(d.pick, '跳槽');
  assert.equal(d.ranked.length, 3);
  // 排名的 index 要能指回原选项，界面靠它对齐
  for (const row of d.ranked) {
    assert.equal(options[row.index], row.option);
  }
});
