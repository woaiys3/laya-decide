/**
 * 读取候选 ONNX 的输入输出契约，判断能否直接替换进 App。
 *
 * App 需要（见 LayaAgent.kt）：
 *   输入  input_ids, attention_mask, marker_pos, marker_mask, qtype
 *   输出  logits, act_logits
 *
 *   node tools/inspect-onnx-io.mjs
 */

import * as ort from 'onnxruntime-node';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';

import { REF_DIR } from './paths.mjs';
const DIR = path.join(REF_DIR, 'candidates');

const REQUIRED_INPUTS = ['input_ids', 'attention_mask', 'marker_pos', 'marker_mask', 'qtype'];

const files = (await readdir(DIR)).filter((f) => f.endsWith('.onnx'));

for (const f of files) {
  const full = path.join(DIR, f);
  console.log(`\n=== ${f} ===`);
  try {
    const session = await ort.InferenceSession.create(full, { executionProviders: ['cpu'] });
    console.log('  输入:');
    for (const n of session.inputNames) console.log(`    - ${n}`);
    console.log('  输出:');
    for (const n of session.outputNames) console.log(`    - ${n}`);

    const missing = REQUIRED_INPUTS.filter((r) => !session.inputNames.includes(r));
    if (missing.length === 0) {
      console.log('  ✅ 接口匹配 —— 可以直接替换进 App');
    } else {
      console.log(`  ❌ 缺输入: ${missing.join(', ')}`);
      console.log('     -> 这是普通 encoder 导出，不含决策头，App 用不了');
    }
    await session.release();
  } catch (e) {
    console.log(`  加载失败: ${e.message}`);
  }
}
