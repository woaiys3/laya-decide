/**
 * 验证工具的共享路径解析。
 *
 * 为什么需要这个：`reference/` 目录**不在仓库里**（里面有 262MB 的模型和
 * 分词器，不适合进 git）。所以任何引用它的脚本都不能写死路径 ——
 * 别人 clone 下来会直接 file-not-found。
 *
 * 查找顺序：
 *   1. 环境变量 LAYA_REF（想放别处就设它）
 *   2. <仓库根>/reference
 *   3. 当前工作目录下的 reference
 *
 * 没有就抛一个能照着做的错误，告诉对方跑 fetch-model.ps1。
 *
 *   import { REF_DIR, EN_DIR, MODEL_PATH, requireModel, requireTokenizer } from './paths.mjs';
 */

import { existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
// tools/ -> server/ -> 仓库根
const repoRoot = path.resolve(here, '..', '..');

const CANDIDATES = [
  process.env.LAYA_REF,
  path.join(repoRoot, 'reference'),
  path.resolve(process.cwd(), 'reference'),
  path.resolve(process.cwd(), '..', 'reference'),
].filter(Boolean);

/** 实际存在的 reference 目录；都没有则为 null。 */
export const REF_DIR = CANDIDATES.find((p) => existsSync(p)) ?? CANDIDATES[0];

/** 分词器与配置所在目录。 */
export const EN_DIR = path.join(REF_DIR, 'en');

/** int4 模型权重。 */
export const MODEL_PATH = path.join(REF_DIR, 'models', 'model_int4.onnx');

const HINT = `
  这些文件不在仓库里（模型 262MB，不适合进 git），需要先下载：

      cd ${repoRoot}
      .\\fetch-model.ps1

  或者手动下载后设置环境变量指向存放位置：

      $env:LAYA_REF = "D:\\你放 reference 的地方"

  下载地址（不需要账号）：
      https://hf-mirror.com/techtheist/laya-onnx/resolve/main/en/
`;

function check(file, label) {
  if (!existsSync(file)) {
    throw new Error(
      `找不到${label}：\n      ${file}\n${HINT}`,
    );
  }
  return file;
}

/** 需要分词器/配置的脚本调这个。 */
export function requireTokenizer() {
  check(path.join(EN_DIR, 'tokenizer.json'), '分词器 tokenizer.json');
  check(path.join(EN_DIR, 'tokenizer_config.json'), '分词器配置 tokenizer_config.json');
  return EN_DIR;
}

/** 需要模型权重的脚本调这个。 */
export function requireModel() {
  const f = check(MODEL_PATH, '模型权重 model_int4.onnx');
  const size = statSync(f).size;
  if (size < 250 * 1024 * 1024) {
    throw new Error(
      `模型文件偏小（${(size / 1048576).toFixed(1)} MB，应该是 262 MB 左右），可能没下完：\n      ${f}\n` +
        `  删掉重下： .\\fetch-model.ps1 -Force`,
    );
  }
  return f;
}

export { repoRoot };
