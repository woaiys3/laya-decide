/**
 * 推理后端 —— 两种模式：
 *
 *   mock : 本地启发式打分，不联网、不下载模型。用来先把整条链路跑通。
 *   real : 调用 @receptron/laya（ONNX Runtime，不需要 PyTorch/Python）。
 *
 * 真实模型首次使用会从 Hugging Face 下载约 1.7GB fp32 权重，
 * 缓存在 ~/.cache/receptron-laya（可用 LAYA_CACHE 覆盖）。
 */

import { buildQuestions, scoreKey, MAX_SCORE } from './laya-core.mjs';

const SCORE_CRITERIA = [
  '很不合适，弊大于利',
  '不太合适，勉强可以',
  '比较合适，值得考虑',
  '明显最佳，强烈推荐',
];

// ---------------------------------------------------------------------------
// 真实模型
// ---------------------------------------------------------------------------

export class RealBackend {
  constructor() {
    this.mode = 'real';
    this._laya = null;
    this._loading = null;
    this._modelInfo = null;
  }

  get modelInfo() {
    return this._modelInfo;
  }

  /**
   * 懒加载：第一次请求时才下载/加载模型。
   * 这样服务能在 3 秒内起来，用户不必对着黑屏等 1.7GB。
   */
  async _ensure() {
    if (this._laya) return this._laya;
    if (this._loading) return this._loading;

    this._loading = (async () => {
      const t0 = Date.now();
      const mod = await import('@receptron/laya');
      const Laya = mod.Laya ?? mod.default?.Laya;

      const opts = {
        onProgress: ({ file, received, total }) => {
          if (total > 0 && received % (64 * 1024 * 1024) < 1024 * 1024) {
            const pct = ((received / total) * 100).toFixed(1);
            console.log(`  [下载] ${file}  ${pct}%`);
          }
        },
      };
      if (process.env.LAYA_MODEL_DIR) opts.modelDir = process.env.LAYA_MODEL_DIR;
      if (process.env.LAYA_SUBFOLDER) opts.subfolder = process.env.LAYA_SUBFOLDER;
      if (process.env.LAYA_CACHE) opts.cacheDir = process.env.LAYA_CACHE;

      const laya = await Laya.load(opts);
      const secs = ((Date.now() - t0) / 1000).toFixed(1);
      console.log(`  [模型] 就绪，用时 ${secs}s`);
      this._laya = laya;
      this._modelInfo = process.env.LAYA_MODEL_DIR ? 'local' : 'convaiinnovations/laya';
      return laya;
    })();

    return this._loading;
  }

  async warmup() {
    await this._ensure();
  }

  async decide(situation, options) {
    const laya = await this._ensure();
    const questions = buildQuestions(options);
    const result = await laya.systemOne({ situation }, questions);

    return {
      answers: result.answers,
      usage: result.usage ?? null,
    };
  }

  async close() {
    if (this._laya?.close) await this._laya.close();
  }
}

// ---------------------------------------------------------------------------
// 演示后端
// ---------------------------------------------------------------------------

export class MockBackend {
  constructor() {
    this.mode = 'mock';
  }

  get modelInfo() {
    return null;
  }

  async warmup() {}

  async decide(situation, options) {
    const answers = {};

    options.forEach((opt, i) => {
      answers[scoreKey(i)] = mockScore(situation, opt, i);
    });

    return {
      answers,
      usage: { inputTokens: Math.round((situation.length + options.join('').length) / 2) },
    };
  }

  async close() {}
}

/**
 * 演示用的启发式打分。
 *
 * ⚠️ 这不是 AI，只是让界面和流程能跑起来的占位实现。
 *    它用「中文 bigram 重合度 + 情境倾向词 + 确定性扰动」凑出分数，
 *    再做一个对比度拉伸，让"明显更贴合情境"的选项真的能拉开差距。
 *    目的是产生看起来合理、每次相同、且区分度像样的排名。
 */
function mockScore(situation, option, index) {
  const s = situation.toLowerCase();
  const o = option.toLowerCase();

  // 1) bigram 重合度 —— 中文用单字重合会把「的/我/在」这类高频字算进去，
  //    导致所有选项分数差不多。bigram 能抓到真正的词。
  const a = bigrams(o);
  const b = new Set(bigrams(s));
  let hits = 0;
  for (const g of a) if (b.has(g)) hits++;
  const overlap = a.length > 0 ? hits / a.length : 0;

  // 2) 情境里的正向/负向倾向词
  const positive = ['想', '希望', '喜欢', '重要', '优先', '愿意', '倾向', '值得', '适合'];
  const negative = ['担心', '风险', '不想', '害怕', '顾虑', '压力', '不能', '避免'];
  const posCount = positive.filter((w) => s.includes(w)).length;
  const negCount = negative.filter((w) => s.includes(w)).length;

  // 3) 确定性扰动 —— 字符串哈希，保证同一个输入每次结果一致
  const jitter = (hash(o) % 1000) / 1000;

  let raw = overlap * 4.0 + (posCount - negCount) * 0.15 + jitter * 0.6;
  raw = Math.min(MAX_SCORE, Math.max(0, raw));

  // 4) 对比度拉伸：把分数推向两端，否则一堆选项全挤在中间看不出差别。
  raw = contrast(raw);

  // 造一个围绕 raw 的分布，让 confidence 有东西可算
  const sigma = 0.5;
  const dist = SCORE_CRITERIA.map((_, k) => Math.exp(-((k - raw) ** 2) / (2 * sigma * sigma)));
  const sum = dist.reduce((acc, d) => acc + d, 0);
  const normalizedDist = dist.map((d) => d / sum);

  // 期望值 = Σ k·p(k)，与真实 score 原语的语义一致
  const expected = normalizedDist.reduce((acc, p, k) => acc + k * p, 0);

  return { score: expected, distribution: normalizedDist, demo: true };
}

/** 把 0..MAX 的分数向两端推，中间区域变化最剧烈。 */
function contrast(x) {
  const c = MAX_SCORE / 2;
  const k = 1.7; // 拉伸强度
  const stretched = c + (x - c) * k;
  return Math.min(MAX_SCORE, Math.max(0, stretched));
}

/** 中英混排都能用的 bigram：相邻两字符一组。 */
function bigrams(str) {
  const cleaned = str.replace(/[\s\p{P}]/gu, '');
  const out = [];
  if (cleaned.length === 1) return [cleaned];
  for (let i = 0; i < cleaned.length - 1; i++) {
    out.push(cleaned.slice(i, i + 2));
  }
  return out;
}

function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}

// ---------------------------------------------------------------------------

/**
 * 按环境变量挑后端。real 模式加载失败会自动退回 mock，而不是让服务起不来。
 */
export async function createBackend({ mode, quiet = false } = {}) {
  const want = (mode ?? process.env.LAYA_MODE ?? 'mock').toLowerCase();

  if (want !== 'real') return new MockBackend();

  try {
    const backend = new RealBackend();
    // 只验证包能不能 import，不在这里下载 1.7GB。
    await import('@receptron/laya');
    if (!quiet) console.log('  [后端] real 模式 —— 首次请求时会加载模型（可能需下载约 1.7GB）');
    return backend;
  } catch (err) {
    console.warn(`  [后端] real 模式不可用（${err.message}），已回退到 mock。`);
    console.warn('         修复：cd server && npm install');
    return new MockBackend();
  }
}
