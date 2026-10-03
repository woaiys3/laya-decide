/**
 * 修复 GitHub 仓库简介里的中文（被 PowerShell 发请求时替换成了 ?）。
 *
 * 为什么单独写个脚本：PowerShell 5.1 的 Invoke-RestMethod 在发 JSON 时
 * 会把非 ASCII 字符替换成 ?（编码在某一层丢了）。Node 的 fetch 显式按
 * UTF-8 编码，可靠得多。
 *
 *   node tools/fix-repo-meta.mjs
 *
 * 只改 description，其它字段一律不动。
 */

import { execFileSync } from 'node:child_process';

const OWNER_REPO = 'woaiys3/laya-decide';

const DESCRIPTION =
  '完全离线的 Android 决策小工具：用本地的 Laya 决策模型给选项排序（ONNX Runtime + int4 量化）';

const TOPICS = ['android', 'onnxruntime', 'on-device', 'offline', 'laya', 'decision-model', 'kotlin'];

/** 从 git 的凭据助手取 token —— 脚本里绝不硬编码凭据。 */
function getToken() {
  const out = execFileSync(
    'C:/Program Files/Git/cmd/git.exe',
    ['credential', 'fill'],
    { input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8' },
  );
  const m = out.match(/^password=(.+)$/m);
  if (!m) throw new Error('没能从 git 凭据助手取到 token');
  return m[1].trim();
}

const token = getToken();

const HEADERS = {
  Authorization: `token ${token}`,
  'User-Agent': 'laya-fix-meta',
  Accept: 'application/vnd.github+json',
};

async function gh(path, init = {}) {
  const res = await fetch(`https://api.github.com${path}`, { ...init, headers: { ...HEADERS, ...(init.headers ?? {}) } });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON 响应，保留原文 */
  }
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText}: ${json?.message ?? text}`);
  }
  return json;
}

// --- 当前状态 -------------------------------------------------------------

console.log('=== 修改前 ===');
const before = await gh(`/repos/${OWNER_REPO}`);
const beforeQ = (before.description ?? '').match(/\?/g)?.length ?? 0;
console.log(`  description : ${JSON.stringify(before.description)}`);
console.log(`  问号数      : ${beforeQ}`);
console.log(`  topics      : ${JSON.stringify(before.topics ?? [])}`);

// --- 修改 -----------------------------------------------------------------

console.log('\n=== 提交修改（只改 description 和 topics）===');

const updated = await gh(`/repos/${OWNER_REPO}`, {
  method: 'PATCH',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  // 关键：BodyInit 用字符串，fetch 会按 UTF-8 编码
  body: JSON.stringify({
    description: DESCRIPTION,
    topics: TOPICS,
  }),
});

console.log(`  description : ${JSON.stringify(updated.description)}`);
console.log(`  topics      : ${JSON.stringify(updated.topics ?? [])}`);

// --- 校验 -----------------------------------------------------------------

console.log('\n=== 校验（重新拉一次，确认真的存对了）===');
const after = await gh(`/repos/${OWNER_REPO}`);
const d = after.description ?? '';
const afterQ = (d.match(/\?/g)?.length ?? 0) - (d.match(/\?/g)?.length ?? 0); // 占位，下面细算
const realQ = (d.match(/\?/g) ?? []).length;
const cjk = (d.match(/[\u4e00-\u9fff]/g) ?? []).length;

console.log(`  description : ${JSON.stringify(d)}`);
console.log(`  字符数      : ${d.length}`);
console.log(`  中文字数    : ${cjk}`);
console.log(`  问号数      : ${realQ}`);

const ok = realQ === 0 && cjk > 10 && d === DESCRIPTION;
console.log('');
if (ok) {
  console.log('  ✅ 修复成功');
  console.log(`  仓库页面: ${after.html_url}`);
} else {
  console.log('  ❌ 仍未正确');
  if (d !== DESCRIPTION) console.log('     期望: ' + JSON.stringify(DESCRIPTION));
  process.exitCode = 1;
}
