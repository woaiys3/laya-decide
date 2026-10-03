/**
 * 逐个检查候选模型能否直接用在我们的 App 里。
 *
 * App 的硬要求（见 app/.../LayaAgent.kt）：
 *   输入  input_ids, attention_mask, marker_pos, marker_mask, qtype
 *   输出  logits（+ act_logits）
 *
 * 所以只有 ONNX 且接口对得上的才能直接用；safetensors 需要自己导出+量化。
 *
 *   node tools/check-laya-candidates.mjs
 */

const CANDIDATES = [
  // ONNX 类
  'onnx-community/laya-multilingual-ONNX',
  'killkli/open-jev-laya-multilingual-onnx',
  'tozp/laya-onnx',
  'ti3x-m/laya-typed-decisions-onnx',
  // 任务微调类（safetensors，需自己导出）
  'BrainboxAI/nitzotz',
  'OidoStudio/laya-mm-guard-v3-gguf',
  'TurkishCodeMan/laya-tr',
  'S4MPL3BI4S/Coding_Decision_Agent',
  'ShaunSpark/laya-mind2web-browser-agent',
  'cklxx/laya-browser',
  'shanaka95/laya-fintiment',
  'Modusnsus/laya-nli-memory-conflict',
  // 更小的重训模型（可能更适合手机）
  'anon767tom/smolaya',
  'mgoeckel/oscar-1-17m',
  'bytesbrains/naderu-laya-150m',
];

const fmtMB = (b) => (b ? (b / 1048576).toFixed(1) + ' MB' : '?');

for (const id of CANDIDATES) {
  let j;
  try {
    const res = await fetch(`https://hf-mirror.com/api/models/${id}?blobs=true`);
    if (!res.ok) {
      console.log(`\n${id}\n  -> HTTP ${res.status}`);
      continue;
    }
    j = await res.json();
  } catch (e) {
    console.log(`\n${id}\n  -> 失败: ${e.message}`);
    continue;
  }

  const files = (j.siblings || []).filter((f) => (f.size || 0) > 100000);
  const onnx = files.filter((f) => f.rfilename.endsWith('.onnx'));
  const st = files.filter((f) => f.rfilename.endsWith('.safetensors'));
  const big = files.some((f) => (f.size || 0) > 900 * 1048576);

  let verdict;
  if (onnx.length) verdict = '✅ 有 ONNX —— 可能可直接用（需核对接口）';
  else if (st.length) verdict = '🔧 只有 safetensors —— 需自己导出 + 量化';
  else verdict = '❓ 无明确权重';

  console.log(`\n=== ${id} ===`);
  console.log(`  downloads=${j.downloads ?? 0}  likes=${j.likes ?? 0}  最后更新 ${(j.lastModified || '').slice(0, 10)}`);
  console.log(`  ${verdict}`);

  const tags = (j.tags || []).filter((t) => /base_model|finetune|dataset|license|mbert|modernbert|multilingual|int8|int4|quant/i.test(t));
  if (tags.length) console.log(`  关键 tag: ${tags.join(', ').slice(0, 170)}`);

  files
    .sort((a, b) => (b.size || 0) - (a.size || 0))
    .slice(0, 7)
    .forEach((f) => console.log(`      ${fmtMB(f.size).padStart(10)}  ${f.rfilename}`));
}
