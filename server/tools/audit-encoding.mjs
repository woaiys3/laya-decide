/**
 * 全面排查 GitHub 上所有元数据的编码问题。
 *
 * 背景：PowerShell 5.1 的 Invoke-RestMethod 发 JSON 时把中文替换成了 ?，
 * 导致仓库简介变成一串问号。同类问题可能出现在任何经它写入的地方，
 * 所以做一次彻底扫描，而不是修一个看一个。
 *
 *   node tools/audit-encoding.mjs
 */

import { execFileSync } from 'node:child_process';

const OWNER_REPO = 'woaiys3/laya-decide';

function getToken() {
  const out = execFileSync('C:/Program Files/Git/cmd/git.exe', ['credential', 'fill'], {
    input: 'protocol=https\nhost=github.com\n\n',
    encoding: 'utf8',
  });
  const m = out.match(/^password=(.+)$/m);
  if (!m) throw new Error('取不到 token');
  return m[1].trim();
}

const HEADERS = {
  Authorization: `token ${getToken()}`,
  'User-Agent': 'laya-audit',
  Accept: 'application/vnd.github+json',
};

async function gh(path) {
  const res = await fetch(`https://api.github.com${path}`, { headers: HEADERS });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json();
}

/** 一个字段的编码体检结果。 */
function inspect(label, text) {
  if (typeof text !== 'string' || text.length === 0) {
    console.log(`  ${label.padEnd(26)} (空)`);
    return { ok: true };
  }
  const q = (text.match(/\?/g) ?? []).length;
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const repl = (text.match(/\uFFFD/g) ?? []).length;
  const mojibake = (text.match(/[ÃÂåæçèéêëìíîïðñòóôõöøùúûüýþÿ]{2,}/g) ?? []).length;

  // 只有中文全没了、只剩问号，才算疑似损坏
  const suspicious = cjk === 0 && q >= 3;
  const ok = !suspicious && repl === 0 && mojibake === 0;

  const flags = [];
  if (q) flags.push(`问号${q}`);
  if (cjk) flags.push(`中文${cjk}`);
  if (repl) flags.push(`⚠️替换符${repl}`);
  if (mojibake) flags.push(`⚠️疑似乱码${mojibake}`);
  if (suspicious) flags.push('❌中文被替换成问号');

  console.log(`  ${label.padEnd(26)} ${ok ? '✅' : '❌'}  ${flags.join('  ')}`);
  return { ok, text };
}

console.log('=================== GitHub 元数据编码体检 ===================\n');

let problems = 0;

// --- 1. 仓库本身 ----------------------------------------------------------
console.log('【仓库】');
const repo = await gh(`/repos/${OWNER_REPO}`);
for (const [k, v] of Object.entries({
  description: repo.description,
  name: repo.name,
  homepage: repo.homepage,
})) {
  const r = inspect(k, v);
  if (!r.ok) problems++;
}
console.log(`  ${'topics'.padEnd(26)} ${(repo.topics ?? []).length ? '✅' : '⚠️'}  ${JSON.stringify(repo.topics ?? [])}`);

// --- 2. 发行版 ------------------------------------------------------------
console.log('\n【发行版】');
const releases = await gh(`/repos/${OWNER_REPO}/releases?per_page=100`);
for (const r of releases) {
  console.log(`  --- ${r.tag_name} (id=${r.id}) ---`);
  for (const [k, v] of Object.entries({ name: r.name, tag_name: r.tag_name, body: r.body })) {
    const res = inspect('  ' + k, v);
    if (!res.ok) problems++;
  }
  for (const a of r.assets) {
    const res = inspect('  asset:' + a.name, a.name);
    if (!res.ok) problems++;
  }
}

// --- 3. 提交信息 ----------------------------------------------------------
console.log('\n【提交信息】');
const commits = await gh(`/repos/${OWNER_REPO}/commits?per_page=100`);
let commitBad = 0;
for (const c of commits) {
  const msg = c.commit.message;
  const first = msg.charCodeAt(0);
  const q = (msg.match(/\?/g) ?? []).length;
  const cjk = (msg.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const bom = first === 0xfeff;
  const bad = bom || (cjk === 0 && q >= 3);
  if (bad) commitBad++;
  console.log(
    `  ${c.sha.slice(0, 7)}  ${bad ? '❌' : '✅'}  ${bom ? 'BOM ' : ''}${q ? `问号${q} ` : ''}中文${cjk}  ` +
      msg.split('\n')[0].slice(0, 32),
  );
}
problems += commitBad;

// --- 4. 标签 --------------------------------------------------------------
console.log('\n【标签 / 分支】');
const tags = await gh(`/repos/${OWNER_REPO}/tags`);
for (const t of tags) {
  const r = inspect('tag:' + t.name, t.name);
  if (!r.ok) problems++;
}
const branches = await gh(`/repos/${OWNER_REPO}/branches`);
for (const b of branches) {
  const r = inspect('branch:' + b.name, b.name);
  if (!r.ok) problems++;
}

// --- 5. 文件路径（中文文件名也可能中招）------------------------------------
console.log('\n【仓库文件路径】');
const tree = await gh(`/repos/${OWNER_REPO}/git/trees/main?recursive=1`);
let pathBad = 0;
for (const e of tree.tree) {
  const q = (e.path.match(/\?/g) ?? []).length;
  const cjk = (e.path.match(/[\u4e00-\u9fff]/g) ?? []).length;
  if (cjk === 0 && q >= 3) {
    pathBad++;
    console.log(`  ❌ ${e.path}`);
  }
}
console.log(`  ${tree.tree.length} 个条目，疑似编码损坏 ${pathBad} 个`);
problems += pathBad;

// --- 结论 -----------------------------------------------------------------
console.log('\n' + '='.repeat(56));
if (problems === 0) {
  console.log('  ✅ 全部正常，没有编码问题');
} else {
  console.log(`  ❌ 发现 ${problems} 处编码问题（见上面标 ❌ 的行）`);
  process.exitCode = 1;
}
