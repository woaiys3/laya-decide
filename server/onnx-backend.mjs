/**
 * 本地 int4 ONNX 后端 —— 和 Android App 用的是同一个模型、同一套序列构造与温度校准。
 *
 * 为什么不直接用 inference.mjs 里的 RealBackend：那个走 @receptron/laya，
 * 默认要下载 1.7 GB 的 fp32 bundle（这台机器上 huggingface.co 还不通）。
 * 仓库里已经有 262 MB 的 int4 导出，App 用的就是它；这里按
 * tools/inspect-model.mjs 的跑法直接喂 onnxruntime-node，保证 MCP 的分数
 * 和手机一致。
 *
 * 序列构造复刻 @receptron/laya dist/sequence.js 的 buildSequence
 * （即 rl_common.build_sequence）：
 *
 *   [CLS] score question: instructions [SEP] [MASK] level0 … [SEP] state [SEP]
 *
 * 改这里之前先看 app/.../agent/SequenceBuilder.kt —— 两边必须一致。
 */

import { existsSync, statSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

import { buildQuestions, scoreKey } from './laya-core.mjs';
import { EN_DIR, MODEL_PATH } from './tools/paths.mjs';

const TOKENIZER_PATH = path.join(EN_DIR, 'tokenizer.json');
const TOKENIZER_CONFIG_PATH = path.join(EN_DIR, 'tokenizer_config.json');
const AGENT_CONFIG_PATH = path.join(EN_DIR, 'rl_agent_config.json');

/** 模型或分词器缺失时抛这个，让上层给 AI 一句能照做的提示。 */
export class ModelMissingError extends Error {}

/** 和 config.temperature_by_options 的键一致：按选项数分桶。 */
function sizeBucket(k) {
  if (k <= 2) return '2';
  if (k <= 5) return '3-5';
  if (k <= 10) return '6-10';
  return '11+';
}

function softmaxWithTemperature(logits, offset, k, temp) {
  let zmax = Number.NEGATIVE_INFINITY;
  const z = new Array(k);
  for (let i = 0; i < k; i++) {
    z[i] = logits[offset + i] / temp;
    if (z[i] > zmax) zmax = z[i];
  }
  let sum = 0;
  const e = new Array(k);
  for (let i = 0; i < k; i++) {
    e[i] = Math.exp(z[i] - zmax);
    sum += e[i];
  }
  if (!(sum > 0)) return new Array(k).fill(1 / k);
  return e.map((x) => x / sum);
}

function confidenceFromProbs(p) {
  const k = p.length;
  if (k < 2) return 1;
  let ent = 0;
  for (const x of p) ent -= x * Math.log(Math.max(x, 1e-12));
  return Math.min(1, Math.max(0, 1 - ent / Math.log(k)));
}

export class LocalOnnxBackend {
  constructor({ quiet = false } = {}) {
    this.mode = 'real';
    this.quiet = quiet;
    this._ort = null;
    this._session = null;
    this._tok = null;
    this._ids = null;
    this._cfg = null;
    this._loading = null;
    this.loadedMs = null;
  }

  get ready() {
    return (
      existsSync(MODEL_PATH) &&
      existsSync(TOKENIZER_PATH) &&
      existsSync(TOKENIZER_CONFIG_PATH) &&
      existsSync(AGENT_CONFIG_PATH)
    );
  }

  get modelPath() {
    return MODEL_PATH;
  }

  status() {
    const present = this.ready;
    return {
      mode: 'real',
      ready: present,
      loaded: this._session !== null,
      loadedMs: this.loadedMs,
      modelPath: MODEL_PATH,
      modelMB: present ? Math.round(statSync(MODEL_PATH).size / 1048576) : null,
    };
  }

  async warmup() {
    await this._ensure();
  }

  async _ensure() {
    if (this._session) return;
    if (this._loading) return this._loading;

    this._loading = (async () => {
      if (!this.ready) {
        throw new ModelMissingError(
          `找不到模型或分词器：\n  ${MODEL_PATH}\n` +
            '  先运行 .\\fetch-model.ps1 下载（约 262 MB，只需要一次）。',
        );
      }

      const t0 = Date.now();
      const [ort, tokMod, tokJson, tokCfg, cfg] = await Promise.all([
        import('onnxruntime-node'),
        import('@huggingface/tokenizers'),
        readFile(TOKENIZER_PATH, 'utf8').then(JSON.parse),
        readFile(TOKENIZER_CONFIG_PATH, 'utf8').then(JSON.parse),
        readFile(AGENT_CONFIG_PATH, 'utf8').then(JSON.parse),
      ]);

      this._ort = ort;
      this._cfg = cfg;
      this._tok = new tokMod.Tokenizer(tokJson, tokCfg);

      const id = (t) => {
        const v = this._tok.token_to_id(t);
        if (v === undefined) throw new Error(`分词器里没有特殊 token ${t}`);
        return v;
      };
      this._ids = {
        cls: id('[CLS]'),
        sep: id('[SEP]'),
        mask: id('[MASK]'),
        pad: id('[PAD]'),
      };

      this._session = await ort.InferenceSession.create(MODEL_PATH, {
        executionProviders: ['cpu'],
      });
      this.loadedMs = Date.now() - t0;
      if (!this.quiet) {
        console.error(`[laya-mcp] int4 模型已加载（${(this.loadedMs / 1000).toFixed(1)}s）`);
      }
    })().catch((err) => {
      // 失败不缓存，下次调用还能重试（比如用户刚把模型下完）。
      this._loading = null;
      throw err;
    });

    return this._loading;
  }

  _encode(text) {
    return this._tok.encode(text, { add_special_tokens: false }).ids;
  }

  /** 逐行复刻 buildSequence，改动前先看 Kotlin 侧的 SequenceBuilder.kt。 */
  _buildSequence(state, q, maxLen, headMaxLen) {
    const ids = this._ids;
    const scrub = (s) => s.split('[MASK]').join(' ');
    const opts = q.crit.map((c, i) => `level ${i}: ${c}`);

    let headIds = this._encode(`${q.t} question: ${scrub(q.ins)}`);
    let optIds = opts.map((o) => [ids.mask, ...this._encode(' ' + scrub(o)).slice(0, 48)]);

    const total = (xs) => xs.reduce((s, o) => s + o.length, 0);
    let optBudget = headMaxLen - total(optIds);
    if (optBudget < 16) {
      const per = Math.max(4, Math.floor((headMaxLen - 16) / Math.max(1, optIds.length)));
      optIds = optIds.map((o) => o.slice(0, per));
      optBudget = headMaxLen - total(optIds);
    }
    headIds = headIds.slice(0, Math.max(8, optBudget));

    const seq = [ids.cls, ...headIds, ids.sep];
    const markers = [];
    for (const o of optIds) {
      markers.push(seq.length);
      seq.push(...o);
    }
    seq.push(ids.sep);

    const room = Math.max(0, maxLen - seq.length - 1);
    const st = this._encode(scrub(typeof state === 'string' ? state : JSON.stringify(state))).slice(0, room);
    seq.push(...st, ids.sep);

    return { ids: seq.slice(0, maxLen), markers: markers.filter((m) => m < maxLen) };
  }

  /**
   * @param {string} situation
   * @param {string[]} options
   * @returns {Promise<{answers: Record<string, {score:number, distribution:number[]}>, usage: object}>}
   */
  async decide(situation, options) {
    await this._ensure();
    const t0 = Date.now();
    const cfg = this._cfg;
    const questions = buildQuestions(options);

    const items = options.map((opt, i) => {
      const q = questions[scoreKey(i)];
      return this._buildSequence(
        situation,
        { t: q.type, ins: q.instructions, crit: q.criteria },
        cfg.max_len,
        cfg.head_max_len,
      );
    });

    const k = questions[scoreKey(0)].criteria.length;
    items.forEach((it, i) => {
      if (it.markers.length !== k) {
        throw new Error(
          `选项「${options[i]}」的档位放不进 head_max_len=${cfg.head_max_len}，请缩短选项文本。`,
        );
      }
    });

    const n = items.length;
    const maxL = Math.max(...items.map((it) => it.ids.length));
    const inputIds = new BigInt64Array(n * maxL).fill(BigInt(this._ids.pad));
    const attention = new BigInt64Array(n * maxL);
    const markerPos = new BigInt64Array(n * k);
    const markerMask = new Uint8Array(n * k);
    const qtype = new BigInt64Array(n).fill(1n); // 1 = score

    let inputTokens = 0;
    items.forEach((it, r) => {
      it.ids.forEach((v, j) => {
        inputIds[r * maxL + j] = BigInt(v);
        attention[r * maxL + j] = 1n;
      });
      inputTokens += it.ids.length;
      it.markers.forEach((m, j) => {
        markerPos[r * k + j] = BigInt(m);
        markerMask[r * k + j] = 1;
      });
    });

    const out = await this._session.run({
      input_ids: new this._ort.Tensor('int64', inputIds, [n, maxL]),
      attention_mask: new this._ort.Tensor('int64', attention, [n, maxL]),
      marker_pos: new this._ort.Tensor('int64', markerPos, [n, k]),
      marker_mask: new this._ort.Tensor('bool', markerMask, [n, k]),
      qtype: new this._ort.Tensor('int64', qtype, [n]),
    });

    const logits = out['logits'].data;
    const temp = cfg.temperature_by_options[`score:${sizeBucket(k)}`] ?? cfg.temperature[1];

    const answers = {};
    const probabilities = [];
    for (let r = 0; r < n; r++) {
      const p = softmaxWithTemperature(logits, r * k, k, temp);
      probabilities.push(p);
      const expected = p.reduce((acc, v, i) => acc + i * v, 0);
      answers[scoreKey(r)] = { score: expected, distribution: p };
    }

    return {
      answers,
      usage: {
        inputTokens,
        latencyMs: Date.now() - t0,
        temperature: temp,
        model: 'int4',
      },
      probabilities,
    };
  }

  async close() {
    if (this._session) {
      await this._session.release();
      this._session = null;
    }
  }
}
