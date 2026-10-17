/**
 * MCP 服务自测 —— 用官方 Client + stdio 真的把 mcp.mjs 拉起来，走一遍协议。
 *
 *   cd server
 *   node --test test/mcp.test.mjs
 *
 * 真实模型默认跳过（要加载 262MB，首次约 6 秒）：
 *   $env:LAYA_REAL_TEST="1"; node --test test/mcp.test.mjs
 *
 * 这个测试同时验证了「stdout 里除 JSON-RPC 外没有杂质」——
 * 客户端能连通并拿到结果，就说明 stdout 没被 console.log 污染。
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const MCP_ENTRY = path.join(here, '..', 'mcp.mjs');

const BASE_ENV = Object.fromEntries(
  Object.entries({ ...process.env, LAYA_MODE: 'mock' }).filter(([, v]) => v !== undefined),
);

async function withClient(env, fn) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [MCP_ENTRY],
    env,
    stderr: 'pipe',
  });
  const client = new Client({ name: 'laya-mcp-test', version: '1.0.0' });
  await client.connect(transport);
  try {
    return await fn(client);
  } finally {
    await client.close().catch(() => {});
  }
}

const textOf = (result) => result.content.find((c) => c.type === 'text')?.text ?? '';

test('tools/list 列出 decide 和 list_backends', async () => {
  await withClient(BASE_ENV, async (client) => {
    const { tools } = await client.listTools();
    assert.deepEqual(
      tools.map((t) => t.name).sort(),
      ['decide', 'list_backends'],
    );

    const decide = tools.find((t) => t.name === 'decide');
    assert.ok(decide.inputSchema.properties.situation, 'decide 要收 situation');
    assert.ok(decide.inputSchema.properties.options, 'decide 要收 options');
    assert.ok(decide.description.includes('参考'), 'description 里要带「仅供参考」的提醒');
  });
});

test('decide 演示模式能秒回排名', async () => {
  await withClient(BASE_ENV, async (client) => {
    const res = await client.callTool({
      name: 'decide',
      arguments: {
        situation: '周末想出去玩，预算500，两个人，不想开车太远。',
        options: ['本市周边爬山', '去隔壁城市逛吃', '露营野餐'],
      },
    });

    assert.ok(!res.isError, textOf(res));
    const text = textOf(res);
    assert.match(text, /推荐：/);
    assert.match(text, /排名：/);

    const s = res.structuredContent;
    assert.equal(s.mode, 'mock');
    assert.equal(s.ranked.length, 3);
    assert.equal(s.ranked[0].rank, 1);
    assert.ok(s.pick);
    assert.ok(s.ranked.every((r) => r.score >= 0 && r.score <= 3));
  });
});

test('decide 参数非法时返回 isError 且信息可读', async () => {
  await withClient(BASE_ENV, async (client) => {
    const one = await client.callTool({
      name: 'decide',
      arguments: { situation: '情境', options: ['只有一个'] },
    });
    assert.equal(one.isError, true);
    assert.match(textOf(one), /至少需要 2 个选项/);

    const dup = await client.callTool({
      name: 'decide',
      arguments: { situation: '情境', options: ['一样', '一样'] },
    });
    assert.equal(dup.isError, true);
    assert.match(textOf(dup), /重复/);

    const empty = await client.callTool({
      name: 'decide',
      arguments: { situation: '   ', options: ['甲', '乙'] },
    });
    assert.equal(empty.isError, true);
    assert.match(textOf(empty), /情境描述不能为空/);
  });
});

test('list_backends 能报告当前模式', async () => {
  await withClient(BASE_ENV, async (client) => {
    const res = await client.callTool({ name: 'list_backends', arguments: {} });
    assert.ok(!res.isError, textOf(res));
    assert.equal(res.structuredContent.mode, 'mock');
    assert.equal(typeof res.structuredContent.ready, 'boolean');
    assert.equal(typeof res.structuredContent.note, 'string');
  });
});

test(
  'decide 真实模型能出结果',
  { skip: process.env.LAYA_REAL_TEST !== '1' },
  async () => {
    await withClient({ ...BASE_ENV, LAYA_MODE: 'real' }, async (client) => {
      const res = await client.callTool({
        name: 'decide',
        arguments: {
          situation: '我拿到一个创业公司 offer，薪资降 30%，但有期权和很大话语权。现在在大厂做螺丝钉，房贷压力不小。',
          options: ['接受这个 offer', '留在现在的大厂', '继续面其他公司'],
        },
      });

      assert.ok(!res.isError, textOf(res));
      const s = res.structuredContent;
      assert.equal(s.mode, 'real');
      assert.equal(s.ranked.length, 3);
      assert.ok(s.ranked.every((r) => r.score >= 0 && r.score <= 3));
      assert.ok(s.usage.latencyMs >= 0);
    });
  },
);
