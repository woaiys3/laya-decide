/**
 * 抓 HuggingFace tokenizers 里 ByteLevel 预分词用的 GPT-2 正则。
 * 这个正则必须逐字复刻 —— 它决定文本怎么切块，切法不同 token id 就不同。
 *
 *   node tools/fetch-regex.mjs
 */

const URLS = [
  'https://raw.githubusercontent.com/huggingface/tokenizers/main/tokenizers/src/pre_tokenizers/byte_level.rs',
  'https://cdn.jsdelivr.net/gh/huggingface/tokenizers@main/tokenizers/src/pre_tokenizers/byte_level.rs',
  'https://raw.gitmirror.com/huggingface/tokenizers/main/tokenizers/src/pre_tokenizers/byte_level.rs',
];

for (const url of URLS) {
  try {
    const res = await fetch(url);
    if (!res.ok) {
      console.log(`${url}\n  -> HTTP ${res.status}`);
      continue;
    }
    const text = await res.text();
    console.log(`${url}\n  -> 拿到 ${text.length} 字节`);

    // 打印所有 Pattern::new 和包含 \p{ 的行
    const lines = text.split('\n');
    lines.forEach((line, i) => {
      if (/Pattern::new|SPLIT_PATTERN|\\p\{/.test(line)) {
        console.log(`  ${String(i + 1).padStart(4)}: ${line.trim()}`);
      }
    });
    break;
  } catch (e) {
    console.log(`${url}\n  -> 失败: ${e.message}`);
  }
}
