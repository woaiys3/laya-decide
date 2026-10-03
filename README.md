# 抉择 · Laya 决策助手

一个 Android App：**导入情境 → 列几个选项 → 让 Laya 帮你选**。

**完全离线。不需要账号、不需要服务器、不联网也能用。**

> ⚠️ **这是个玩具，不是决策工具。**
>
> 没人应该拿软件决定要不要辞职、要不要分手。这模型在开放式选择上的判断力本来就有限
> （详见下面「它擅长什么」那一节），**准确率低是它的固有属性，不是 bug**。
>
> 当玩具玩反而更好——它有主见、会给出你没想过的排序、而且经常错得很有意思。
> 别当真就行。

---

## 它是什么

输入一段背景（你在纠结什么），列出几个候选选项，App 在**手机本地**跑 Laya 决策模型，
对每个选项打分并排序，给出推荐和分差。

模型是 [Laya](https://github.com/NandaKishorM/laya)（Convai Innovations 开源，Apache-2.0），
一个非自回归的 System 1 决策模型：它不生成文字，而是在一次前向传播里对"带类型的问题"
直接给出概率化答案。

---

## 先说清楚：它擅长什么，不擅长什么

**这一点很重要，但既然当玩具玩，就别太当回事。**

Laya 的基座检查点**在开放式选择上的判断力有限**。官方 README 自己写得很直白：

> The base checkpoints are near chance on typed-decisions zero-shot... 
> Laya is a fast base to specialise, not a zero-shot decision engine.

我在真实模型上做过实验（`server/tools/prompt-experiment.mjs`），用 4 个真实决策场景、
5 种提问措辞对比，结论是：**没有任何一种措辞能让模型变得果断**，置信度普遍落在
0.09–0.14，前两名分差经常只有 0.01–0.05。

所以这个 App 的设计原则是**诚实呈现**，而不是假装 AI 很懂你：

| 机制 | 作用 |
|---|---|
| 置信度人话标签 | 「模型比较有把握 / 倾向不明显 / 基本在猜」，阈值按实测标定 |
| 排序不可信警告 | 当所有选项极差 < 0.10 时，明确告诉你"这等于没选出来" |
| 分数不写成档位 | 不写"得分 2.36 = 比较合适"。分布接近均匀时，档位标签是误导的 |
| 底部固定说明 | 每次都提示这是玩具、别做重大决定 |

**它适合**：当个乐子。给几个选项让它排个序，看它怎么想，偶尔会有意外角度。

**它不适合**：任何真实决策。它会给你一个排序，但那个排序可能只是噪声。

> 顺带说，社区里也确实**没有**能解决这个场景的现成微调模型。我查过 144 个 Laya 相关仓库，
> 唯一真正微调过的官方检查点训的是发票处理、客服、安全事件这类工作流，跟"帮人选人生道路"
> 完全不是一个分布。所以"准确率低"这件事短期内无解——除非你自己标数据去微调。

---

## 快速开始

### 1. 装 App

拿桌面上或 `app/app/build/outputs/apk/release/` 里的 arm64 包：

```
app-arm64-v8a-release.apk     现代手机（2017 年后基本都是）
app-armeabi-v7a-release.apk   老手机
```

装完打开，它是**空的** —— 没有模型。下一步拿模型。

### 2. 拿模型（二选一）

**方式 A：让 App 自己下（最简单）**

设置 → 端侧模型 → 「下载模型（约 262MB）」。走 hf-mirror.com 镜像，
不需要注册任何账号。下完即可离线使用。

**方式 B：从电脑传（快，且适合没有 Wi-Fi 或镜像不通的情况）**

```powershell
D:\laya\push-model.ps1                 # 默认推到模拟器/已连接设备
```

或者手动：

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
# 注意推到 /data/local/tmp —— Android 11+ 的 shell 读写不了 Android/data
& $adb push model_int4.onnx /data/local/tmp/laya-model.onnx
```

App 会自动按这个顺序找模型：

1. `/sdcard/Android/data/com.laya.decide/files/models/model_int4.onnx`（App 自己下载的落点）
2. `/data/data/com.laya.decide/files/models/model_int4.onnx`
3. `/data/local/tmp/laya-model.onnx`（adb 部署落点）

### 3. 用

设置里选「端侧模型（完全离线）」→ 填情境 → 填至少两个选项 → 点「让 Laya 帮我选」。

首次推理会把模型读进内存（模拟器上约 6 秒，真机更快），之后就一直常驻。

---

## 三种后端

| 模式 | 说明 | 需要什么 |
|---|---|---|
| **端侧模型** | 真实 Laya 跑在手机上，完全离线 | 模型文件（262MB） |
| PC 服务 | 把推理放到电脑上，手机通过局域网调用 | 电脑跑 `server/server.mjs` |
| 演示模式 | 纯本地启发式打分，**不是 AI** | 什么都不用 |

演示模式的存在是为了让你在下载模型之前就能看清界面和流程。App 里会明确标注它不是真实推理。

---

## 关于模型

| 项 | 值 |
|---|---|
| 权重来源 | [`techtheist/laya-onnx`](https://huggingface.co/techtheist/laya-onnx)（非官方 ONNX 导出，Apache-2.0） |
| 文件 | `en/model_int4.onnx`，262 MB |
| 量化 | int4（MatMulNBits，block 32，对称量化） |
| 为什么选 int4 而不是 int8 | int4 的质量几乎不掉（AUROC 0.72 vs fp32 的 0.71），而且比 int8 更快、体积只有一半 |
| 编码器 | ModernBERT-large，421M 参数 |
| 上下文 | 512 token |
| 协议 | Apache-2.0 |

**为什么模型不打包进 APK**：262MB 远超 Google Play 的 200MB 上限，而且会让每次更新
都要重下。所以做成首次获取。

---

## 项目结构

```
D:\laya\
├── README.md               ← 本文件
├── PROJECT.md              ← 设计决策与理由（为什么用打分而不是多选，等等）
├── BUILD.md                ← 编译、签名、踩坑记录
├── build.ps1               ← 一键编译
├── push-model.ps1          ← 把模型推到设备
├── reference\              ← 参考实现产物（分词器、配置、ONNX 权重、Python 源码）
├── screenshots\            ← 模拟器实测截图
├── server\                 ← PC 端推理服务（可选）+ 全部验证工具
│   ├── server.mjs
│   ├── laya-core.mjs
│   ├── inference.mjs
│   ├── test\core.test.mjs
│   └── tools\
│       ├── gen-tokenizer-fixture.mjs   ← 生成分词器逐 id 夹具
│       ├── gen-sequence-fixture.mjs    ← 生成序列逐 id 夹具
│       ├── inspect-model.mjs           ← 检查模型输入输出契约
│       ├── cross-check.mjs             ← 用 Kotlin 的序列喂真实模型
│       ├── prompt-experiment.mjs       ← 提问措辞的量化对比实验
│       └── probe-hf.mjs / fetch-regex.mjs
└── app\                    ← Android 工程
    └── app\src\
        ├── main\
        │   ├── assets\                    tokenizer.json + rl_agent_config.json（3.5MB）
        │   ├── java\com\laya\decide\
        │   │   ├── MainActivity.kt
        │   │   ├── agent\
        │   │   │   ├── LayaAgent.kt        端侧 ONNX 推理（复刻参考实现）
        │   │   │   ├── SequenceBuilder.kt  序列构造（[CLS]…[MASK]opt…[SEP]state[SEP]）
        │   │   │   ├── ModelFiles.kt       模型查找与下载
        │   │   │   └── ModelManager.kt     加载管理（单次、线程安全）
        │   │   ├── tokenizer\
        │   │   │   ├── Tokenizer.kt        ByteLevel BPE 的 Kotlin 移植
        │   │   │   └── Json.kt             极简 JSON 解析（为了 JVM 可测）
        │   │   ├── core\                   决策逻辑（纯 Kotlin，不依赖 Android）
        │   │   ├── backend\                后端抽象 + 三种实现
        │   │   └── data\HistoryStore.kt    SQLite 历史
        │   └── res\
        └── test\                          50 个 JVM 单测
```

---

## 正确性是怎么保证的

端侧移植最容易出错的地方是**分词和序列构造**：只要有一个 token id 不对，
模型看到的输入就变了，判断也就变了 —— 而且它不会报错，只会安静地给你错的答案。

所以这两处都用参考实现生成了**逐 id 夹具**，Kotlin 必须完全匹配：

```powershell
cd D:\laya\server
node tools/gen-tokenizer-fixture.mjs   # 64 条：中文、emoji、特殊 token、空白边界
node tools/gen-sequence-fixture.mjs    # 8 个场景：含截断与预算收缩分支
cd D:\laya\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" testDebugUnitTest
```

测试覆盖：

| 测试 | 数量 | 验什么 |
|---|---|---|
| `TokenizerFixtureTest` | 8 | 分词结果与 `@huggingface/tokenizers` 逐 id 一致 |
| `SequenceBuilderTest` | 4 | 序列构造与参考 JS 逐 id 一致 |
| `LayaConfigTest` | 7 | 温度分桶边界、置信度必须用校准后分布 |
| `DecisionEngineTest` | 30 | 排序、置信度、输入校验、排名可信度判据 |
| `SequenceDumpTest` | 1 | dump 真实序列供交叉验证 |

**改了 `Tokenizer.kt` / `SequenceBuilder.kt` / 提问措辞之后，必须重新生成夹具并重跑测试。**

---

## 编译

见 [BUILD.md](BUILD.md)。最短路径：

```powershell
D:\laya\build.ps1            # debug + release + 单测
D:\laya\build.ps1 -Install   # 顺便装到设备
```

---

## 许可

App 代码：随你处置。  
Laya 模型权重：Apache-2.0，版权归 Convai Innovations。  
ONNX 导出：来自 `techtheist/laya-onnx`，同样 Apache-2.0。
