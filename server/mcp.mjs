#!/usr/bin/env node
/**
 * 抉择 · 本地 MCP 服务
 *
 * 把 App 的决策能力暴露给 AI 客户端（Claude Desktop / Cursor / Cherry Studio 等）：
 * 给一个情境和几个选项，返回排序和推荐。传输用 stdio，只在本地跑，不开端口。
 *
 *   node mcp.mjs                    # 有模型就用真实 int4 模型，没有就退到演示模式
 *   $env:LAYA_MODE="real"; node mcp.mjs
 *   $env:LAYA_MODE="mock"; node mcp.mjs
 *
 * ⚠️ stdout 是 JSON-RPC 通道，任何 console.log 都会打断协议。
 *    日志一律走 console.error —— 文件末尾还有一道保险。
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';

import {
  MAX_SCORE,
  SITUATION_CHAR_LIMIT,
  OPTION_CHAR_LIMIT,
  MAX_OPTIONS,
  MIN_OPTIONS,
  assembleDecision,
  cleanOptions,
  validateInput,
} from './laya-core.mjs';
import { MockBackend } from './inference.mjs';
import { LocalOnnxBackend, ModelMissingError } from './onnx-backend.mjs';

const SERVER_NAME = 'laya-decide';
const SERVER_VERSION = '1.0.0';

// ---------------------------------------------------------------------------
// 后端选择
// ---------------------------------------------------------------------------

/** auto：有 262MB 的 int4 模型就用真模型，没有就用演示模式。 */
function resolveMode() {
  const raw = String(process.env.LAYA_MODE ?? 'auto').toLowerCase();
  if (raw === 'real' || raw === 'mock') return raw;
  if (raw !== 'auto') {
    console.error(`[laya-mcp] 未知 LAYA_MODE=${raw}，按 auto 处理`);
  }
  return new LocalOnnxBackend().ready ? 'real' : 'mock';
}

/** @type {null | import('./inference.mjs').MockBackend | LocalOnnxBackend} */
let backendRef = null;

async function getBackend() {
  if (backendRef) return backendRef;

  if (resolveMode() === 'real') {
    const backend = new LocalOnnxBackend();
    if (!backend.ready) {
      throw new ModelMissingError(
        '端侧模型还没下载。先在这台电脑上运行 .\\fetch-model.ps1（约 262 MB），' +
          '或者把 LAYA_MODE 设成 mock 先用演示模式。',
      );
    }
    backendRef = backend;
  } else {
    backendRef = new MockBackend();
  }
  return backendRef;
}

// ---------------------------------------------------------------------------
// 结果格式化
// ---------------------------------------------------------------------------

function confidenceLabel(conf) {
  if (conf >= 0.25) return '模型比较有把握';
  if (conf >= 0.12) return '模型倾向不明显';
  return '模型基本在猜';
}

function spreadOf(ranked) {
  if (ranked.length < 2) return null;
  return ranked[0].score - ranked[ranked.length - 1].score;
}

function formatDecision(decision, mode) {
  const rows = decision.ranked;
  const top = rows[0];
  const spread = spreadOf(rows);
  const lines = [];

  lines.push(`推荐：${decision.pick ?? '—'}`);

  const bits = [`档位 ${top.score.toFixed(2)} / ${MAX_SCORE.toFixed(2)}`];
  bits.push(confidenceLabel(top.confidence));
  if (decision.margin != null) {
    bits.push(`分差 ${decision.margin.toFixed(2)}（${decision.marginLabel}）`);
  }
  lines.push(bits.join(' · '));
  lines.push('');
  lines.push('排名：');
  rows.forEach((r, i) => {
    lines.push(
      `  ${i + 1}. ${r.option}   ${r.score.toFixed(2)}   确定度 ${(r.confidence * 100).toFixed(0)}%`,
    );
  });

  if (mode === 'mock') {
    lines.push('');
    lines.push('注意：这是演示模式（本地启发式），分数不是模型算的。设 LAYA_MODE=real 可切到真实模型。');
  }
  if (spread != null && spread < 0.10) {
    lines.push('');
    lines.push(
      '注意：所有选项得分极差 < 0.10，说明模型没分辨出高低，这个排序基本是噪声，建议换个说法再问一次。',
    );
  }
  if (decision.note) {
    lines.push('');
    lines.push(decision.note);
  }

  lines.push('');
  lines.push('提醒：这是未微调的基座模型，判断力有限；结果只作参考，不要当成结论。');
  return lines.join('\n');
}

function structured(decision, mode, usage) {
  return {
    mode,
    pick: decision.pick,
    margin: decision.margin,
    marginLabel: decision.marginLabel,
    closeCall: decision.closeCall,
    note: decision.note,
    spread: spreadOf(decision.ranked),
    ranked: decision.ranked.map((r, i) => ({
      rank: i + 1,
      option: r.option,
      score: Number(r.score.toFixed(4)),
      normalized: Number(r.normalized.toFixed(4)),
      confidence: Number(r.confidence.toFixed(4)),
    })),
    usage: {
      inputTokens: usage?.inputTokens ?? null,
      latencyMs: usage?.latencyMs ?? null,
      temperature: usage?.temperature ?? null,
    },
  };
}

function errorResult(message) {
  return {
    isError: true,
    content: [{ type: 'text', text: message }],
  };
}

function friendlyError(err) {
  if (err instanceof ModelMissingError) return err.message;
  const msg = err?.message ?? String(err);
  return `推理失败：${msg}\n（模型文件、按需加载或内存问题都可能；详见 stderr 日志。）`;
}

// ---------------------------------------------------------------------------
// MCP 工具
// ---------------------------------------------------------------------------

const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION });

server.registerTool(
  'decide',
  {
    title: '在几个选项里做抉择',
    description:
      '在给定情境下评估多个选项并排序，返回最推荐的一个。用于「我该不该…」「选哪个好」这类需要权衡的决策。' +
      '注意：模型判断力有限，结果应作为参考而非结论；如果所有选项分数几乎一样，说明它没分辨出来，排序不可信。',
    inputSchema: {
      situation: z
        .string()
        .describe(
          `情境/背景描述。要写清楚约束、顾虑、已知条件。上限 ${SITUATION_CHAR_LIMIT} 字，超出会被拒绝。`,
        ),
      options: z
        .array(z.string())
        .describe(
          `候选选项，${MIN_OPTIONS}-${MAX_OPTIONS} 个，每个不超过 ${OPTION_CHAR_LIMIT} 字。`,
        ),
    },
    outputSchema: {
      mode: z.enum(['real', 'mock']),
      pick: z.string().nullable(),
      margin: z.number().nullable(),
      marginLabel: z.string(),
      closeCall: z.boolean(),
      note: z.string(),
      spread: z.number().nullable(),
      ranked: z.array(
        z.object({
          rank: z.number(),
          option: z.string(),
          score: z.number(),
          normalized: z.number(),
          confidence: z.number(),
        }),
      ),
      usage: z.object({
        inputTokens: z.number().nullable(),
        latencyMs: z.number().nullable(),
        temperature: z.number().nullable(),
      }),
    },
  },
  async ({ situation, options }) => {
    // 校验先用 laya-core 的版本：长度、数量、重复项都在那儿统一处理。
    const check = validateInput(situation, options);
    if (!check.ok) return errorResult(check.error);

    const cleaned = cleanOptions(options);

    try {
      const backend = await getBackend();
      const { answers, usage } = await backend.decide(situation, cleaned);
      const decision = assembleDecision(cleaned, answers);
      const mode = backend.mode;

      return {
        content: [{ type: 'text', text: formatDecision(decision, mode) }],
        structuredContent: structured(decision, mode, usage),
      };
    } catch (err) {
      console.error(`[laya-mcp] decide 失败：${err?.message ?? err}`);
      return errorResult(friendlyError(err));
    }
  },
);

server.registerTool(
  'list_backends',
  {
    title: '查看当前推理后端',
    description:
      '告诉 AI 现在用的是真实模型还是演示模式、模型在不在、路径是什么。不会加载模型，也不会下载任何东西。',
    inputSchema: {},
    outputSchema: {
      mode: z.enum(['real', 'mock']),
      configured: z.string(),
      ready: z.boolean(),
      loaded: z.boolean(),
      modelPath: z.string().nullable(),
      modelMB: z.number().nullable(),
      note: z.string(),
    },
  },
  async () => {
    const configured = String(process.env.LAYA_MODE ?? 'auto');
    const mode = resolveMode();
    const status =
      backendRef instanceof LocalOnnxBackend
        ? backendRef.status()
        : new LocalOnnxBackend().status();

    const note =
      mode === 'real'
        ? `真实 int4 模型${status.loaded ? '，已加载' : '，首次调用 decide 时加载（约 6 秒）'}。未微调，判断力有限。`
        : '演示模式：本地启发式打分，不是 AI。设置 LAYA_MODE=real 并下载模型后才是真实推理。';

    return {
      content: [
        {
          type: 'text',
          text:
            `模式：${mode}（LAYA_MODE=${configured}）\n` +
            `模型：${status.ready ? `${status.modelMB} MB · ${status.modelPath}` : '未下载'}\n` +
            note,
        },
      ],
      structuredContent: {
        mode,
        configured,
        ready: status.ready,
        loaded: status.loaded,
        modelPath: status.ready ? status.modelPath : null,
        modelMB: status.modelMB,
        note,
      },
    };
  },
);

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

const transport = new StdioServerTransport();
await server.connect(transport);
console.error(
  `[laya-mcp] 已启动（stdio）· 模式=${resolveMode()} · 工具=decide, list_backends`,
);

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, async () => {
    try {
      await backendRef?.close?.();
    } catch {
      // 退出路径上不再报错
    }
    process.exit(0);
  });
}

// stdout 只能跑 JSON-RPC。以后谁顺手加一句 console.log，也不会把客户端搞挂。
console.log = (...args) => console.error(...args);
