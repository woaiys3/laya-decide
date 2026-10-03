/**
 * 细查 oscar-1 系列：用 typed-decisions 数据集训练的决策模型，但骨干是更小的编码器。
 *
 * 为什么关注它：Laya 的 421M 参数是手机上最大的负担。如果 17M/32M/150M 的模型
 * 在这类任务上够用，包体积和速度都会好一个数量级。
 *
 *   node tools/check-oscar-family.mjs
 */

const REPOS = [
  'mgoeckel/oscar-1-17m',
  'mgoeckel/oscar-1-32m',
  'mgoeckel/oscar-1-150m',
  'mgoeckel/oscar-1-400m',
  'bytesbrains/naderu-laya-150m',
  'anon767tom/smolaya',
];

const fmtMB = (b) => (b ? (b / 1048576).toFixed(1) + ' MB' : '?');

for (const id of REPOS) {
  try {
    const res = await fetch(`https://hf-mirror.com/api/models/${id}?blobs=true`);
    if (!res.ok) {
      console.log(`\n${id} -> HTTP ${res.status}`);
      continue;
    }
    const j = await res.json();

    console.log(`\n=== ${id} ===`);
    console.log(`  downloads=${j.downloads ?? 0}  likes=${j.likes ?? 0}  ${(j.lastModified || '').slice(0, 10)}`);

    const interesting = (j.tags || []).filter((t) =>
      /base_model|dataset|license|int8|int4|quant|typed-decision/i.test(t),
    );
    if (interesting.length) console.log(`  ${interesting.join('\n  ').slice(0, 400)}`);

    // 拉 config.json 看架构与参数量
    try {
      const cfgRes = await fetch(`https://hf-mirror.com/${id}/resolve/main/config.json`);
      if (cfgRes.ok) {
        const cfg = await cfgRes.json();
        console.log(
          `  架构: ${cfg.architectures?.join(',') ?? '?'}  hidden=${cfg.hidden_size ?? '?'}  layers=${cfg.num_hidden_layers ?? '?'}  vocab=${cfg.vocab_size ?? '?'}`,
        );
      }
    } catch {}

    (j.siblings || [])
      .filter((f) => (f.size || 0) > 50000)
      .sort((a, b) => (b.size || 0) - (a.size || 0))
      .slice(0, 6)
      .forEach((f) => console.log(`      ${fmtMB(f.size).padStart(10)}  ${f.rfilename}`));
  } catch (e) {
    console.log(`\n${id} -> 失败: ${e.message}`);
  }
}
