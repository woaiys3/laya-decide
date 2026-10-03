#!/usr/bin/env node
/**
 * Laya 决策服务 —— 跑在 PC 上，手机通过局域网调用。
 *
 *   node server.mjs                       # 演示模式，秒级可用
 *   $env:LAYA_MODE="real"; node server.mjs # 真实模型（首次下载约 1.7GB）
 *
 * 环境变量：
 *   LAYA_MODE       mock | real        默认 mock
 *   PORT            监听端口            默认 8931
 *   LAYA_MODEL_DIR  本地 ONNX 目录（跳过下载）
 *   LAYA_SUBFOLDER  multilingual | typed-decisions
 *   LAYA_CACHE      模型缓存目录
 */

import http from 'node:http';
import os from 'node:os';
import { createBackend } from './inference.mjs';
import {
  assembleDecision,
  cleanOptions,
  validateInput,
  SITUATION_CHAR_LIMIT,
  MAX_OPTIONS,
} from './laya-core.mjs';

const PORT = Number(process.env.PORT ?? 8931);
const HOST = process.env.HOST ?? '0.0.0.0';

/** 请求体上限 —— 防止有人往这个口子灌数据。 */
const MAX_BODY = 64 * 1024;

const backend = await createBackend({});

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  // 手机上的 WebView / 浏览器会先发预检，顺手支持一下。
  if (req.method === 'OPTIONS') {
    return send(res, 204, null);
  }

  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);

  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return send(res, 200, {
        ok: true,
        mode: backend.mode,
        model: backend.modelInfo,
        limits: {
          situationChars: SITUATION_CHAR_LIMIT,
          maxOptions: MAX_OPTIONS,
        },
      });
    }

    if (req.method === 'POST' && url.pathname === '/decide') {
      const body = await readJson(req);

      const situation = typeof body?.situation === 'string' ? body.situation.trim() : '';
      const rawOptions = Array.isArray(body?.options) ? body.options : [];

      const check = validateInput(situation, rawOptions);
      if (!check.ok) {
        return send(res, 400, { ok: false, error: check.error });
      }

      const options = cleanOptions(rawOptions);
      const t0 = Date.now();
      const { answers, usage } = await backend.decide(situation, options);
      const ms = Date.now() - t0;

      const decision = assembleDecision(options, answers);

      console.log(
        `[决策] ${options.length} 个选项 → "${decision.pick}" ` +
          `(${decision.marginLabel}) ${ms}ms`,
      );

      return send(res, 200, {
        ok: true,
        mode: backend.mode,
        decision,
        usage: usage ? { ...usage, latencyMs: ms } : { latencyMs: ms },
      });
    }

    return send(res, 404, { ok: false, error: `没有这个接口: ${req.method} ${url.pathname}` });
  } catch (err) {
    console.error('[错误]', err);
    const status = err.statusCode ?? 500;
    return send(res, status, { ok: false, error: err.message ?? '服务内部错误' });
  }
});

server.listen(PORT, HOST, () => {
  const lan = lanAddresses();
  console.log('');
  console.log('  Laya 决策服务已启动');
  console.log(`  模式      ${backend.mode}${backend.mode === 'mock' ? '  (演示用启发式打分，不是 AI)' : ''}`);
  console.log(`  本机      http://127.0.0.1:${PORT}`);
  for (const ip of lan) {
    console.log(`  局域网    http://${ip}:${PORT}   ← 手机上填这个`);
  }
  if (lan.length === 0) {
    console.log('  局域网    (没检测到局域网地址，手机可能连不上)');
  }
  console.log('');
  console.log('  自检:  curl.exe http://127.0.0.1:' + PORT + '/health');
  console.log('');
});

// ---------------------------------------------------------------------------

function send(res, status, payload) {
  if (payload === null) {
    res.writeHead(status, corsHeaders());
    return res.end();
  }
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    ...corsHeaders(),
  });
  res.end(body);
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        const err = new Error('请求体过大');
        err.statusCode = 413;
        reject(err);
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      if (text.trim() === '') return resolve({});
      try {
        resolve(JSON.parse(text));
      } catch {
        const err = new Error('请求体不是合法 JSON');
        err.statusCode = 400;
        reject(err);
      }
    });
    req.on('error', reject);
  });
}

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address);
    }
  }
  return out;
}

// 优雅退出，顺便让模型释放内存。
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    console.log('\n正在关闭…');
    server.close();
    await backend.close();
    process.exit(0);
  });
}
