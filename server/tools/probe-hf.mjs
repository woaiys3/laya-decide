/**
 * 探查 Hugging Face 上的 Laya ONNX 仓库结构 —— 搞清楚要下载哪些文件、多大。
 *
 *   node tools/probe-hf.mjs
 */

const REPOS = [
  'receptron/laya-onnx',
  'techtheist/laya-onnx',
  'convaiinnovations/laya',
  'onnx-community/laya-ONNX',
];

async function listRepo(repo) {
  const url = `https://huggingface.co/api/models/${repo}?blobs=true`;
  try {
    const res = await fetch(url);
    if (!res.ok) {
      console.log(`\n${repo}  -> HTTP ${res.status}`);
      return;
    }
    const j = await res.json();
    console.log(`\n=== ${repo} ===`);
    console.log(`  gated=${j.gated}  private=${j.private}  downloads=${j.downloads ?? '?'}`);
    const files = j.siblings ?? [];
    let total = 0;
    for (const f of files) {
      const size = f.size ?? 0;
      total += size;
      const mb = size > 0 ? (size / 1024 / 1024).toFixed(1) + ' MB' : '?';
      console.log(`  ${mb.padStart(12)}  ${f.rfilename}`);
    }
    console.log(`  ${'---'.padStart(12)}`);
    console.log(`  ${(total / 1024 / 1024).toFixed(1).padStart(12)} MB 合计`);
  } catch (e) {
    console.log(`\n${repo}  -> 失败: ${e.message}`);
  }
}

for (const r of REPOS) {
  await listRepo(r);
}
