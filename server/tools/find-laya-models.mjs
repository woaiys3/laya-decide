/**
 * 搜索 Hugging Face 上所有基于 Laya 的模型（含微调版），并分析能不能用。
 *
 *   node tools/find-laya-models.mjs
 *
 * 判断标准：
 *   1. 是不是真的基于 Laya（看 base_model / tags / config）
 *   2. 有没有可直接用的 format（ONNX 最好，safetensors 需要自己导出）
 *   3. 多大（能不能塞进手机）
 */

const ENDPOINTS = [
  'https://hf-mirror.com/api/models?search=laya&limit=100&full=false',
  'https://hf-mirror.com/api/models?filter=laya&limit=100&full=false',
  'https://hf-mirror.com/api/models?author=convaiinnovations&limit=100&full=false',
];

const seen = new Map();

for (const url of ENDPOINTS) {
  try {
    const res = await fetch(url);
    if (!res.ok) {
      console.log(`跳过 ${url} -> HTTP ${res.status}`);
      continue;
    }
    const list = await res.json();
    for (const m of list) {
      if (!seen.has(m.id)) seen.set(m.id, m);
    }
  } catch (e) {
    console.log(`跳过 ${url} -> ${e.message}`);
  }
}

// 过滤：只要跟 laya 沾边的
const candidates = [...seen.values()].filter((m) => {
  const id = (m.id || '').toLowerCase();
  const tags = (m.tags || []).map((t) => String(t).toLowerCase());
  return id.includes('laya') || tags.some((t) => t.includes('laya'));
});

console.log(`\n搜到 ${candidates.length} 个跟 laya 相关的仓库\n`);

// 按下载量排序
candidates.sort((a, b) => (b.downloads || 0) - (a.downloads || 0));

for (const m of candidates) {
  const tags = (m.tags || []).join(', ');
  console.log(`--- ${m.id} ---`);
  console.log(`  downloads=${m.downloads ?? '?'}  likes=${m.likes ?? '?'}  updated=${(m.lastModified || '').slice(0, 10)}`);
  if (tags) console.log(`  tags: ${tags.slice(0, 160)}`);
  console.log('');
}

// 对下载量最高的几个，拉详细文件清单
const interesting = candidates.filter((m) => (m.downloads || 0) > 0).slice(0, 12);
const also = candidates.filter((m) => !interesting.includes(m)).slice(0, 8);
const toInspect = [...interesting, ...also];

console.log('\n================ 文件清单（判断能不能直接用在手机上）================\n');

for (const m of toInspect) {
  try {
    const res = await fetch(`https://hf-mirror.com/api/models/${m.id}?blobs=true`);
    if (!res.ok) {
      console.log(`${m.id} -> HTTP ${res.status}\n`);
      continue;
    }
    const j = await res.json();
    const files = (j.siblings || []).filter((f) => (f.size || 0) > 200000);

    const hasOnnx = files.some((f) => f.rfilename.endsWith('.onnx'));
    const hasSafetensors = files.some((f) => f.rfilename.endsWith('.safetensors'));
    const hasQuant = files.some((f) => /int4|int8|quant|q4|q8/i.test(f.rfilename));

    let verdict;
    if (hasOnnx && hasQuant) verdict = '✅ 可能可直接用（有 ONNX + 量化）';
    else if (hasOnnx) verdict = '🟡 有 ONNX，但未量化（体积大）';
    else if (hasSafetensors) verdict = '🔧 只有 safetensors，需要自己导出+量化';
    else verdict = '❓ 没找到权重文件';

    console.log(`${m.id}   [downloads=${j.downloads ?? 0}]`);
    console.log(`  ${verdict}`);
    files
      .sort((a, b) => (b.size || 0) - (a.size || 0))
      .slice(0, 6)
      .forEach((f) => console.log(`      ${((f.size || 0) / 1048576).toFixed(1).padStart(8)} MB  ${f.rfilename}`));
    console.log('');
  } catch (e) {
    console.log(`${m.id} -> 失败: ${e.message}\n`);
  }
}
