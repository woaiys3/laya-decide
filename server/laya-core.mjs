/**
 * 决策核心逻辑 —— 纯函数，不依赖网络和模型，可以单独测试。
 *
 * 设计要点（详见 PROJECT.md）：
 *   不用 choice 单选，改用「每个选项独立打一个序数分，再排序」。
 *   理由：choice 找的是语义最相近的标签，而决策要的是"哪一个最有利"。
 */

/** 序数评分档位。改这里会同时影响提问和归一化。 */
export const SCORE_CRITERIA = [
  '很不合适，弊大于利',
  '不太合适，勉强可以',
  '比较合适，值得考虑',
  '明显最佳，强烈推荐',
];

export const MAX_SCORE = SCORE_CRITERIA.length - 1; // 3

/**
 * 背景字数上限 —— 按模型真实预算量出来的，不是拍脑袋。
 *
 * 512 token 上下文里，固定开销（[CLS] + 问题头 + [SEP] + 4 个档位 + [SEP]）
 * 约 102 token，留给背景 409 token；中文实测 1 token ≈ 0.76 字
 * （见 tools/measure-limits.mjs），也就是约 314 字。取 300 留一点余量。
 *
 * 之前是 1200 —— UI 让写 1200，模型只读前 314 字，剩下 74% 被静默丢掉。
 */
export const SITUATION_CHAR_LIMIT = 300;

/**
 * 单个选项的字数上限。
 *
 * 选项文字进「问题头」，预算 = head_max_len(192) - 档位(72) = 120 token。
 * 实测 85 字选项 ≈ 107 token，装得下；100 字就会被截断。
 */
export const OPTION_CHAR_LIMIT = 85;

/** 模型自己建议单个 choice 问题不超过约 20 个选项。 */
export const MAX_OPTIONS = 20;

export const MIN_OPTIONS = 2;

/**
 * 构造要发给 Laya 的问题集。
 *
 * 一次 systemOne 调用把所有问题批量跑完 —— 因此 5 个选项只是
 * 多 5 个 question，仍然是一次前向传播，不是 5 次推理。
 *
 * @param {string[]} options
 * @returns {Record<string, object>}
 */
export function buildQuestions(options) {
  /** @type {Record<string, object>} */
  const questions = {};

  // 每个选项一个独立的 score 问题。
  // 选项文本放进 instructions，让模型评估「这个选项在该情境下有多好」。
  // 措辞必须和 Android 端 LayaAgent.kt 一致 —— 这是 prompt-experiment 里
  // 区分度最好的那一版（B 直白提问，见 PROJECT.md 第三节）。
  options.forEach((opt, i) => {
    questions[scoreKey(i)] = {
      type: 'score',
      instructions: `面对上述情境，选择「${opt}」有多好？`,
      criteria: SCORE_CRITERIA,
    };
  });

  return questions;
}

export function scoreKey(i) {
  return `opt_${i}`;
}

/**
 * 把模型返回的原始分数整理成排名。
 *
 * @param {string[]} options
 * @param {Record<string, {score?: number, distribution?: number[]}>} answers
 * @returns {Array<{option: string, index: number, score: number, normalized: number, confidence: number}>}
 */
export function rankOptions(options, answers) {
  const rows = options.map((option, index) => {
    const a = answers?.[scoreKey(index)] ?? {};
    const raw = typeof a.score === 'number' && Number.isFinite(a.score) ? a.score : 0;
    const score = clamp(raw, 0, MAX_SCORE);
    return {
      option,
      index,
      score,
      normalized: MAX_SCORE > 0 ? score / MAX_SCORE : 0,
      confidence: confidenceFromDistribution(a.distribution),
    };
  });

  // 分数降序；同分时保持用户输入顺序（稳定），避免结果看起来随机跳。
  rows.sort((a, b) => (b.score - a.score) || (a.index - b.index));
  return rows;
}

/**
 * 用分布算「这个评分有多确定」。
 *
 * 分布越集中，说明模型越肯定；越平坦说明它在犹豫。
 * 这是 score 原语相对 choice 的一个额外好处 —— 每项都能拿到
 * 自己的确定度，而不是选项之间互相 softmax 出来的虚高概率。
 *
 * @param {number[]|undefined} dist
 * @returns {number} 0..1
 */
export function confidenceFromDistribution(dist) {
  if (!Array.isArray(dist) || dist.length === 0) return 0;
  const sum = dist.reduce((s, x) => s + (Number.isFinite(x) ? x : 0), 0);
  if (sum <= 0) return 0;

  const p = dist.map((x) => (Number.isFinite(x) ? x : 0) / sum);
  // 归一化熵：0 = 完全确定，1 = 完全均匀
  const n = p.length;
  if (n <= 1) return 1;
  let h = 0;
  for (const pi of p) {
    if (pi > 0) h -= pi * Math.log(pi);
  }
  const hMax = Math.log(n);
  const certainty = hMax > 0 ? 1 - h / hMax : 1;
  return clamp(certainty, 0, 1);
}

/**
 * 组装最终决策对象：排名 + 推荐项 + 分差判断 + 人话说明。
 *
 * @param {string[]} options
 * @param {Record<string, object>} answers
 */
export function assembleDecision(options, answers) {
  const ranked = rankOptions(options, answers);

  if (ranked.length === 0) {
    return { ranked: [], pick: null, margin: null, marginLabel: 'none', closeCall: false, note: '没有选项。' };
  }

  const pick = ranked[0].option;
  const margin = ranked.length > 1 ? ranked[0].score - ranked[1].score : null;

  // 分差语义：按归一化分差判断，跟具体档位数解耦。
  const normMargin = margin === null ? 1 : margin / MAX_SCORE;
  let marginLabel;
  let closeCall;
  if (margin === null) {
    marginLabel = 'single';
    closeCall = false;
  } else if (normMargin < 0.10) {
    marginLabel = '胶着';
    closeCall = true;
  } else if (normMargin < 0.25) {
    marginLabel = '有倾向';
    closeCall = false;
  } else {
    marginLabel = '明确';
    closeCall = false;
  }

  return {
    ranked,
    pick,
    margin,
    marginLabel,
    closeCall,
    note: buildNote(ranked, marginLabel),
  };
}

function buildNote(ranked, marginLabel) {
  const first = ranked[0];
  if (ranked.length === 1) {
    return `只有一个选项，模型给出 ${first.score.toFixed(2)} / ${MAX_SCORE}。`;
  }
  const second = ranked[1];

  if (marginLabel === '胶着') {
    return `「${first.option}」和「${second.option}」分数几乎持平（${first.score.toFixed(2)} vs ${second.score.toFixed(2)}）。模型在这两个之间没有明显偏好，建议按你自己的风险偏好来定。`;
  }
  if (marginLabel === '有倾向') {
    return `模型略偏向「${first.option}」（${first.score.toFixed(2)} vs ${second.score.toFixed(2)}），但没有拉开明显差距。`;
  }
  return `模型明确倾向「${first.option}」（${first.score.toFixed(2)} vs ${second.score.toFixed(2)}）。`;
}

/**
 * 输入校验 —— 在花钱/花时间推理之前先挡掉明显有问题的输入。
 *
 * @returns {{ok: true} | {ok: false, error: string}}
 */
export function validateInput(situation, options) {
  if (typeof situation !== 'string' || situation.trim().length === 0) {
    return { ok: false, error: '情境描述不能为空。' };
  }
  if (situation.length > SITUATION_CHAR_LIMIT) {
    return { ok: false, error: `情境描述过长（${situation.length} 字），请压缩到 ${SITUATION_CHAR_LIMIT} 字以内。` };
  }
  if (!Array.isArray(options)) {
    return { ok: false, error: '选项格式不对。' };
  }

  const cleaned = options.map((o) => (typeof o === 'string' ? o.trim() : '')).filter((o) => o.length > 0);

  if (cleaned.length < MIN_OPTIONS) {
    return { ok: false, error: `至少需要 ${MIN_OPTIONS} 个选项。` };
  }
  if (cleaned.length > MAX_OPTIONS) {
    return { ok: false, error: `最多支持 ${MAX_OPTIONS} 个选项。` };
  }
  const tooLong = cleaned.find((o) => o.length > OPTION_CHAR_LIMIT);
  if (tooLong) {
    return { ok: false, error: `选项「${tooLong.slice(0, 12)}…」太长（${tooLong.length} 字），请压缩到 ${OPTION_CHAR_LIMIT} 字以内。` };
  }
  // 重复选项会让打分变成无意义的并列，提前拦住。
  const seen = new Set();
  for (const o of cleaned) {
    if (seen.has(o)) return { ok: false, error: `选项「${o}」重复了。` };
    seen.add(o);
  }

  return { ok: true };
}

export function cleanOptions(options) {
  const out = [];
  const seen = new Set();
  for (const o of options) {
    const s = typeof o === 'string' ? o.trim() : '';
    if (s.length > 0 && !seen.has(s)) {
      seen.add(s);
      out.push(s);
    }
  }
  return out;
}

function clamp(x, lo, hi) {
  return Math.min(hi, Math.max(lo, x));
}
