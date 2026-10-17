# 交接文档

> 写给下一个接手的人（或下一个上下文窗口里的我）。
> 最初：2026-10-03，提交 `73af891`；**2026-10-17 更新：旧任务做完 + MCP 增强 + 1.1 换签名**。
>
> **先读第一节：那里原本是三件待办，现在标了完成状态。** 只剩「任务 3-B（放宽 max_len）」
> 和「arm64 真机验证」——两件都需要 arm64 真机，本机只有 x86_64 模拟器，所以没硬做。
> 第二节之后是背景资料，遇到具体问题时再查。

---

# 第一节 · 你的任务

## 状态（2026-10-17，本地 commit，**未推送**）

| 任务 | 状态 | commit |
|---|---|---|
| 1 · 删掉 PC 服务模式 | ✅ 完成 | `0ffc856` |
| 2 · 加本地 MCP 服务 | ✅ 完成 | `30e2bb3` |
| 3 · 字数上限诚实对齐（方案 A） | ✅ 完成 | `c1574be` |
| 4 · App 内置 MCP 服务（开关/端口/局域网） | ✅ 完成 | `b7fc918` |
| 5 · 1.1：地址复制 / 通知保活 / 新签名密钥 | ✅ 完成 | `5ad503e` |
| 3-B · 放宽 `max_len` 到 1024 | ⏸ 未做（需真机实测） | — |
| arm64 真机推理验证 | ⏸ 仍未验证 | — |

> ⚠️ **任务 2 和原计划有一处偏差，必须知道**：`inference.mjs` 里的 `RealBackend`
> 走的是 `@receptron/laya`，默认要下载 **1.7 GB fp32 bundle**（本机
> `~/.cache/receptron-laya` 是空的，`huggingface.co` 又不通）。所以 MCP 的真实推理
> **没有复用它**，而是新增 `server/onnx-backend.mjs`，直接跑
> `reference/models/model_int4.onnx` —— 和 App 同一个文件、同一套序列构造与温度校准。
> `inference.mjs` 原样保留（`MockBackend` 仍被 MCP 的演示模式使用）。
>
> 另：`laya-core.mjs` 里的提问措辞当时还是旧的「适配程度」版（变体 A），
> 已经对齐 App 实际采用的「面对上述情境，选择「X」有多好？」（变体 B）。两端的
> `buildQuestions` 现在逐字一致。

> **追加（`b7fc918`）：MCP 现在有两种跑法。** 电脑端 `server/mcp.mjs`（stdio）不变；
> App 设置里新增「MCP 服务」：启用开关、允许局域网访问、端口（默认 **8940**），
> **默认关闭**。手机端走 Streamable HTTP（`POST /mcp`，不提供 SSE），始终用端侧模型；
> 局域网开关关掉时只绑 `127.0.0.1`，打开才绑 `0.0.0.0`。没有鉴权，界面里已写明。
>
> **1.1（`5ad503e`）起**：地址可以一键/长按复制；开启后由**前台 Service**在通知栏常驻保活
> （带「停止」动作，Android 13+ 会申请通知权限），切后台也能继续调；8.0+ 的 FGS 类型是
> `dataSync`。别从最近任务里把 App 划掉。

---

## 任务 1：删掉「PC 服务」模式 —— ✅ 已完成（`0ffc856`）

> 以下正文保留，作为「当时为什么这么改」的记录。

### 为什么

用户的判断：**有电脑且方便的人不会用这个手机 App**。留着一个"手机连电脑推理"的模式，
对不了解的人只有误导作用——他会以为必须开电脑。

所以 App 的推理后端只保留两种：

| 模式 | 留下吗 | 说明 |
|---|---|---|
| 端侧模型 | ✅ 留 | 真实推理，主路径 |
| 演示模式 | ✅ 留 | 本地启发式，下载模型前先用它看界面 |
| **PC 服务（局域网）** | ❌ **删** | 本次任务 |

### 具体要删什么

**Android 侧**（`app/app/src/main/`）：

1. `java/com/laya/decide/backend/InferenceBackend.kt`
   - 删掉整个 `class HttpLayaBackend`（含 `normalize()`、`get()/post()`、`readBody()`）
   - `enum class BackendMode` 删掉 `HTTP("http")` 这一项
   - `fun backendFor(...)` 的 `when` 里删掉 `BackendMode.HTTP` 分支
   - **保留** `DemoBackend`、`LocalBackend`、`BackendException`

2. `java/com/laya/decide/MainActivity.kt`
   - 删 `testConnection()` 方法
   - 删 `currentServer()`、`saveServer()`（如果 `onPause` 里有调用也删）
   - 删 `prefs` 里 `KEY_SERVER` / `DEFAULT_SERVER` 相关逻辑
   - 删 `setupSettings()` 里对 `etServer`、`btnTest` 的事件绑定
   - `refreshStatusChip()` / `updateModelStatus()` 的 `when` 去掉 `HTTP` 分支
   - `handleDebugIntent()`（debug 包专用）去掉 `server` 参数处理
   - 删掉 `R.id.rbHttp` 的处理

3. `res/layout/activity_main.xml`
   - 删 `<EditText android:id="@+id/etServer">`
   - 删 `<TextView android:id="@+id/btnTest">`
   - 删 `RadioButton rbHttp`

4. `res/values/strings.xml` —— 删这些：
   ```
   backend_http, label_server, hint_server, btn_test, testing, test_ok, test_fail
   ```
   同时检查 `backend_local` / `backend_demo` 的文案里有没有提到"电脑"、"局域网"，有就改。

5. `res/xml/network_security_config.xml`
   - 这个文件只为放行明文 HTTP 而存在。删之前先确认**模型下载走的是 HTTPS**
     （`ModelFiles.kt` 里的 `SOURCES` 是 `https://`，是的）。
   - 所以可以删掉 `network_security_config.xml`，
     并去掉 `AndroidManifest.xml` 里的 `android:networkSecurityConfig` 属性。
   - ⚠️ **但 `INTERNET` 权限必须保留** —— 模型要靠它下载。

6. `data/HistoryStore.kt`
   - `modeKey` 字段保留（历史记录里要标"端侧"/"演示"），
     但读旧数据时可能出现 `"http"`，`BackendMode.fromKey()` 已会兜底成 `DEMO`，不用改。

**PC 侧**：

7. `server/server.mjs` —— **整个删掉**。这是那个 HTTP 接口，App 不再调它。
8. `server/package.json` 里的 `start` 脚本改成指向新的 MCP 入口（见任务 2）。
9. `server/test/core.test.mjs` —— **保留**。它测的是 `laya-core.mjs`，
   那个文件被 MCP 复用，测试必须留着。

**文档**：

10. `README.md`
    - 「三种后端」表格删掉 PC 服务那一行
    - 「快速开始」里如果提到"电脑跑 server.mjs"，删掉
11. `BUILD.md` / `PROJECT.md` 里提到 HTTP 接口、`10.0.2.2`、8931 端口的地方，一并修改
    （`PROJECT.md` 第五节「接口协议（PC 服务模式用）」整节删掉）

### 验收

```powershell
cd D:\laya\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" assembleDebug testDebugUnitTest
```
- 编译通过，50 个单测仍全过
- 装到模拟器上，设置面板里**只剩两个选项**（演示模式 / 端侧模型）
- 端侧推理仍正常出结果

---

## 任务 2：加一个本地 MCP 服务，让 AI 能用「抉择」 —— ✅ 已完成（`30e2bb3`）

### 为什么

用户想要：**AI 能像人一样用这个 App 的判断能力** —— 给它一个问题背景和几个选项，
它拿到排序结果。这样 AI 在回答"我该不该 X"这类问题时，可以真的调用一次模型，
而不是自己瞎猜。

### 形态

- **MCP（Model Context Protocol）服务，本地跑**，stdio 传输
- 用户的 AI 客户端（Claude Desktop / Cursor / Cherry Studio 等）配置一行就能接上
- 不做 HTTP，不做网络暴露

### 技术选型（已验证可行）

| 项 | 值 |
|---|---|
| SDK | `@modelcontextprotocol/sdk`，最新 **1.32.1**（`npm view` 确认可装） |
| 传输 | `StdioServerTransport`（本地进程，AI 客户端拉起） |
| 模型 | **PC 侧 ONNX**，复用 `server/inference.mjs` 的 `RealBackend` |
| 决策逻辑 | **直接复用** `server/laya-core.mjs` |
| 模型文件 | `fetch-model.ps1` 已经能下，路径 `reference/models/model_int4.onnx`（262MB，本机已有） |

> **为什么不用手机上的模型**：MCP 客户端在电脑上，够不到手机的进程。
> 让 PC 自己跑 ONNX 最简单，而且 `server/` 里那套代码已经跑通过（21 个单测）。

### 可以复用的现成东西

`server/laya-core.mjs` 已经导出：

```
SCORE_CRITERIA / MAX_SCORE / SITUATION_CHAR_LIMIT / OPTION_CHAR_LIMIT
MAX_OPTIONS(20) / MIN_OPTIONS(2)
buildQuestions(options)        → 给 Laya 的问题集
scoreKey(i)
rankOptions(options, answers)  → 排名
confidenceFromDistribution(d)
assembleDecision(options, answers) → 最终决策对象（含 pick/margin/marginLabel/closeCall/note）
validateInput(situation, options)  → 输入校验
cleanOptions(options)
```

`server/inference.mjs` 已经导出：

```
createBackend({ mode })  → MockBackend 或 RealBackend
RealBackend.decide(situation, options) → { answers, usage }
```

**所以 MCP 服务的核心逻辑基本是"把这两个接起来"，不需要重写模型调用。**

### 要暴露的 MCP 工具

功能要和 App 对齐，一个都不能少。**建议一个工具就够**：

#### `decide`

一次完整的抉择。

```jsonc
{
  "name": "decide",
  "description": "在给定情境下评估多个选项并排序，返回最推荐的一个。用于\"我该不该…\"这类需要权衡的决策。注意：模型判断力有限，结果应作为参考而非结论。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "situation": {
        "type": "string",
        "description": "情境/背景描述。要写清楚约束、顾虑、已知条件。上限 1200 字。"
      },
      "options": {
        "type": "array",
        "items": { "type": "string" },
        "description": "候选选项，2-20 个，每个不超过 60 字。"
      }
    },
    "required": ["situation", "options"]
  }
}
```

返回（`content` 里放一段人/AI 都能读的文本 + `structuredContent` 放原始对象）：

```
推荐：继续面其他公司
档位 1.78 / 3.00 · 模型基本在猜 · 分差 0.03（胶着）

排名：
  1. 继续面其他公司   1.78  确定度 2%
  2. 留在现在的大厂   1.75  确定度 3%
  3. 接受这个 offer   1.19  确定度 1%

注意：所有选项得分极差 < 0.10，说明模型没分辨出高低，
这个排序基本是噪声，建议换个说法再问一次。
```

#### `list_backends`（可选，但推荐）

告诉 AI 当前可用什么、模型在不在。返回 `mode`（real/mock）、模型是否就绪、
以及一句提醒"这是未微调的基座模型，判断力有限"。

### 实现要点（踩过的坑，直接用）

1. **stdio 传输时绝对不要往 stdout 打印任何东西**。MCP 用 stdout 传 JSON-RPC，
   一行多余的 `console.log` 就会让客户端报协议错误。
   要打日志请用 `console.error` 或写文件。

2. **模型加载是懒加载 + 慢**（首次约 6 秒，还要下载 262MB）。
   必须在 `tools/call` 里处理，并且：
   - 缺模型时返回清晰的错误（"先跑 `fetch-model.ps1`"），别让 AI 看到堆栈
   - 建议服务启动时**不**下载、只在 `decide` 首次调用时按需加载
   - **不要在 MCP 里偷偷下 262MB**，直接报错让用户自己跑脚本

3. **错误要用 MCP 的 `isError: true` 返回**，不要 throw（throw 会让客户端断开）。

4. **`options` 的校验直接用 `validateInput`**，它已经处理了长度/数量/重复项。

5. **提示词措辞不要自己改**。`laya-core.mjs` 里的 `buildQuestions` 是实测挑出来的
   （见 `PROJECT.md`），改了要重跑 `server/tools/prompt-experiment.mjs`。

### 目录结构建议

**建议直接写 `server/mcp.mjs`**，复用 `server/node_modules`。

不要新开 `mcp/` 目录——`@receptron/laya` 和 `onnxruntime-node` 已经装在 `server/` 里了，
新目录要重装 300MB 依赖。

### 怎么配到客户端（写进 README）

Claude Desktop（Windows）`claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "laya-decide": {
      "command": "node",
      "args": ["D:\\laya\\server\\mcp.mjs"]
    }
  }
}
```

Cursor：`.cursor/mcp.json` 同样的结构。

### 验收

写一个自测脚本（用 SDK 的 Client + StdioClientTransport，或者直接灌 JSON-RPC），验证：

- `tools/list` 能列出 `decide`
- `decide` 用模拟模式（`LAYA_MODE=mock`）能秒回
- `decide` 用真实模型能出结果（首次会慢）
- 参数非法时返回 `isError: true` 且信息可读
- **stdout 里除 JSON-RPC 外没有任何杂质**

---

## 任务 3：背景和选项"能写的字太少"的反馈 —— ✅ 方案 A 已完成（`c1574be`），方案 B 未做

### 反馈内容

有用户反馈：**背景和选项能写的字太少。**

### 先看实测数字（别急着调大 UI 上限）

跑 `node server/tools/measure-limits.mjs` 得到：

```
=== 背景 ===
  固定开销: [CLS]1 + 问题头27 + [SEP]1 + 4个档位72 + [SEP]1 = 102 tokens
  留给背景: max_len(512) - 102 - 1 = 409 tokens
  中文实测:   50 字 → 66 tokens     87 字 → 113 tokens
              （1 token ≈ 0.76 个中文字）

  → 实际能进模型  : 约 314 个中文字
  → 当前 UI 上限  : 1200 字
  → ⚠️ 超出部分被静默丢弃约 886 字（74%）

=== 选项 ===
  选项文字进「问题头」，预算 = head_max_len(192) - 档位(72) = 120 tokens
    60 字选项 → 需 82 tokens   ✅ 装得下
    85 字选项 → 需 107 tokens  ✅ 装得下
   100 字选项 → 需 122 tokens  ❌ 会被截断
  → 当前 UI 上限 60 字 还算保守，理论上可放到约 85 字
```

**所以反馈是准的，但真正的问题比"字太少"更糟**：

- 背景不是"太少"，而是**UI 允许 1200 字、模型只读 314 字，剩下 74% 被静默丢掉**。
  用户以为自己写清楚了，模型根本没看到。**这是欺骗性的，必须修。**
- 选项的 60 字确实偏保守，但只差一点（可到 85）。

### 三个可能的修法

#### A. 诚实对齐（成本最低，建议先做这个）

- 把 `SITUATION_CHAR_LIMIT` 从 **1200 降到 300**（或 320，留点余量）
- 把 `OPTION_CHAR_LIMIT` 从 **60 提到 85**
- 输入框下加**实时字数计数器**（`已写 128 / 300 字`），接近上限时变色
- 达到上限后禁止继续输入（`InputFilter.LengthFilter`），而不是让它写进去再丢

**好处**：不再骗人，用户知道模型到底看到了多少。
**坏处**：表面上是"改得更小了"，可能再次被投诉。所以**必须在界面上说明原因**
（例如提示"模型上下文有限，背景最多 300 字；写不下请只保留关键约束"）。

要改的地方（两端必须同步，否则校验打架）：
- `server/laya-core.mjs` 的 `SITUATION_CHAR_LIMIT` / `OPTION_CHAR_LIMIT`
- `app/.../core/DecisionEngine.kt` 的同名常量
- 对应的注释与 `README.md` 说明

#### B. 抬高 max_len（可能最有效，但要实测）

关键事实：**ONNX 导出的序列长度是动态的，范围 8–8192**（见 `techtheist/laya-onnx` 的
README）。所以 `rl_agent_config.json` 里的 `max_len: 512` **只是一个配置值**，
改成 1024 或 2048 **不需要重新导出模型**，App 现在就能跑。

实测换算：
```
max_len = 1024  → 背景 921 tokens ≈ 709 字（2.3 倍）
max_len = 2048  → 背景 1945 tokens ≈ 1497 字
```

**但有个必须验证的风险**：模型是在 `max_len=512` 上训练的，喂更长的序列是
**分布外输入**，质量可能退化。

验证方法（用 `server/tools/` 现成的工具）：
1. 临时把 `reference/en/rl_agent_config.json` 的 `max_len` 改成 1024
   （`assets/rl_agent_config.json` 也要同步，那是 App 实际读的）
2. 用 `node tools/inspect-model.mjs` 跑几个固定案例，对比 512 和 1024 下的分数
3. 用 `server/tools/prompt-experiment.mjs` 的思路，比较两种配置的 spread/margin/conf
4. **如果 1024 下分数明显更散或更离谱，就说明退化了，回到方案 A**

⚠️ 注意显存/内存：序列长度翻倍会显著增加推理开销（注意力是 O(n²)）。手机上是 CPU 推理，
1024 下可能慢一倍以上。**必须实测手机上 1024 的耗时**，不能只看桌面。

#### C. 换 1024 上下文的 checkpoint（代价大，不建议现在做）

`laya-typed-decisions`（int8 **532MB**）和 `laya-multilingual`（int8 **873MB**）
的 `max_len` 都是 1024。但：
- 体积从 262MB 涨到 532MB+，下载和内存都翻倍
- typed-decisions 训的是发票/客服/安全事件，域外无收益（见第十二节）
- multilingual 是英文弱、多语言强，中文会更差

**除非 B 被证伪，否则不要走这条路。**

### 建议的执行顺序

1. ✅ **A 已做**（`c1574be`）：背景 1200 → **300**、选项 60 → **85**，输入框加实时计数和硬上限，
   UI 上写明原因。`measure-limits.mjs` 现在直接读 `laya-core` 的常量，会检查 UI 上限没超过模型预算。
2. ⏸ **B 未做**：把 `max_len` 改到 1024 实测质量，**还要 arm64 真机测耗时**。本机只有
   x86_64 模拟器，所以留到有真机的时候。
3. 如果 B 被证明不退化，再按实测把背景上限放宽（1024 下约 700 字）。

### 验收

- 背景输入到上限时**打不进去**（而不是打进去被丢）
- 界面上能看到当前字数 / 上限
- `node server/tools/measure-limits.mjs` 输出的"实际能进模型"数字，与 UI 上限一致
  （允许 UI 上限略小于模型上限，但**绝不能大**）

---


# 第二节 · 用户的态度（必须遵守）

用户最后把定位改成了**「就是个玩具」**：

1. **不要再追求准确率。** 原话："反正也没有真人把这个软件当参与重大抉择，都是玩玩的，
   正确率低，戏剧性玩的话更好。"
   → 不要主动做微调、不要加复杂功能、不要说"我可以让它更准"。

2. **不要打大动干戈。** 原话："没必要这么打大动干戈。"
   → 修一个问题就收工。不要为了验证去起进程长时间观察，不要顺手写一堆审计脚本。
   上一个人（我）因为过度验证被明确批评过两次。

3. **不要影响机器上其他项目。** 原话："我的另一个项目也要用这个环境打包，
   你不要影响到它。"
   → `D:\dsh\aibendi` 是用户的另一个 Android 工程，共用同一套工具链。
   不要改它的文件，不要长时间占用 Gradle 守护进程 / 内存 / 端口。
   **构建完记得 `gradle --stop`**。

4. **该说的实话要说。** 界面上「模型基本在猜」和「排序不可信」警告条是用户认可的
   设计，不要为了"好看"删掉。MCP 工具的 description 里也要带同样的提醒。

---

# 第三节 · 当前状态

| 项 | 状态 |
|---|---|
| 端侧推理 | ✅ 跑通（Android 11 模拟器 x86_64，加载约 6 秒） |
| 与参考实现一致性 | ✅ 分词器 64 条、序列构造 8 场景，**逐 token id 一致** |
| 单元测试 | ✅ Android 50 + PC 核心 21 + MCP 5，全过 |
| 两种后端 | ✅ 端侧 / 演示模式 均实测（PC 服务已删） |
| MCP 服务 | ✅ 电脑端 stdio + App 内置 HTTP，均用官方 Client 实测 |
| MCP 保活 | ✅ 前台 Service + 常驻通知，App 切后台后仍能调 |
| 字数上限 | ✅ 背景 300 / 选项 85，计数与硬上限已在模拟器验证 |
| Release 签名 | ✅ 1.1 用新密钥签，apksigner 验签通过 |
| ABI 拆包 | ✅ 133MB → 34.9MB |
| GitHub 仓库 | ✅ 138 个 tree 条目，密钥确认未上传 |
| Release 附件 | ⚠️ 仍是旧代码的 v1.0.0，这批改动**还没发版** |

**版本**：`versionCode 2` / `versionName "1.1"`

仓库：https://github.com/woaiys3/laya-decide
发行版：https://github.com/woaiys3/laya-decide/releases/tag/v1.0.0
本地：`D:\laya`（桌面副本：`C:\Users\Administrator\Desktop\Laya决策助手\`）

> ⚠️ 仓库可能被别人动过。我最后一次推送时发现远程有 2 个不是我提交的 commit
> （`docs(README):` 风格，加了又删了 dshaa.xin 的链接，净变化为零）。
> **推之前先 `git fetch`，不要强推。**

### 签名密钥（**别弄丢**）

> 1.1 起不再用演示密钥，换成 2026-10-17 新生成的 release 密钥。

| 项 | 值 |
|---|---|
| keystore | `app/laya-release.jks`（PKCS12、RSA 4096、有效期 10000 天） |
| alias | `laya-release` |
| 证书 SHA-256 | `97:22:3B:12:21:F2:86:18:1C:CD:58:D0:1C:11:6D:04:1D:3B:B2:AB:7B:F5:9E:77:0F:2D:C2:55:35:CD:5E:49` |
| 口令 | `app/keystore.properties`（**已被 .gitignore 排除**，不在 git 里） |
| 桌面备份 | `C:\Users\Administrator\Desktop\Laya签名密钥备份\`（keystore + properties + 说明.txt） |

⚠️ 密钥或口令丢了，就再也无法用同一签名更新这个 App，只能换包名或让用户卸载重装。
**keystore 和 properties 都不要提交、不要外传。** 换电脑前先把桌面备份整个带走。

旧的 `app/laya-decide.jks`（演示密钥，签过 v1.0.0 及以前）还留着，但新构建已经不用它。
因为签名变了，**从 v1.0.0 升级必须先卸载旧版**（会清掉历史记录和 App 内下载的模型，
模型重下即可）。第一次安装 1.1 的用户不受影响。

1.1 的 arm64 包（本地构建，未发版）：
`C:\Users\Administrator\Desktop\抉择-1.1-arm64.apk`
（34.94 MB，SHA256 `ee8200c52fb25c8d849b628ad25c2dbf75b79b372314d9346b904e74f7f3854c`）

---

# 第四节 · 唯一未验证的高风险项

**arm64 真机的端侧推理从没跑过。** 手上只有 x86_64 模拟器。

如果用户报"闪退"或"卡在加载中"，第一件事：

```
adb logcat -s LayaModel:* AndroidRuntime:E
```

Release 说明里已如实写明这一点。

---

# 第五节 · 环境事实（别重复探测）

| 项 | 值 |
|---|---|
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`（JAVA_HOME 已设） |
| Android SDK | `C:\Android`（platform 33/34，build-tools 34/35） |
| **Gradle** | `D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat`（借用用户的，**别改**） |
| 模拟器启动 | **必须 `-gpu guest`**（见下） |
| AVD | `dsh-test30`（Android 11 / API 30 / x86_64） |
| adb | `C:\Android\platform-tools\adb.exe` |
| OS | Windows 10，**只有 PowerShell 5.1，没有 pwsh 7** |
| 网络 | `github.com` HTTPS 被 SNI 阻断（API 和 codeload 正常）；`huggingface.co` 不通，走 `hf-mirror.com` |
| 内存 | 16 GB |
| 显卡 | AMD R5 230（2013 年），**无 CUDA** |
| Python | 只有 Store 占位 stub，**没有 pip / torch / transformers** |

**工具链一个字都没改过**：Gradle 目录文件时间全是 1980（zip 解压产物），
Android SDK 最新改动是 2026-08-29。`D:\dsh\aibendi` 的配置也是 8 月的。

**模拟器启动命令**：

```powershell
& C:\Android\emulator\emulator.exe -avd dsh-test30 -no-snapshot -no-audio -no-boot-anim -gpu guest
```

- ❌ `-gpu swiftshader_indirect` —— 缺 `opengl32sw.dll`，直接崩
- ❌ `-gpu auto` —— 时好时坏
- ✅ `-gpu guest` —— 唯一稳定

---

# 第六节 · GitHub 相关

- **认证走 SSH**，不是 HTTPS（HTTPS 被阻断）
  - 密钥 `~/.ssh/id_ed25519`，已注册（key id `165273918`）
  - `~/.ssh/config` 配了 `github.com` 走 22 端口、`github-ssh` 走 443 备用
  - remote：`git@github.com:woaiys3/laya-decide.git`
- **缓存凭据**：Windows 凭据管理器有 `git:https://github.com`（用户名 `woaiys3`），
  用 `git credential fill` 可以取，调 API 用
- **Token 泄露**：用户在对话里贴过一个 `ghp_...`。我**从未使用**，也提醒过他吊销。
  **不确定他吊销了没有。**
- 调 API 的工具：`server/tools/fix-repo-meta.mjs`、`server/tools/audit-encoding.mjs`

---

# 第七节 · 七个已踩过的坑

详细版在 `BUILD.md` 的「踩坑记录」（14 条）。最容易重复犯的：

1. **`.ps1` 必须 UTF-8 带 BOM。** PS 5.1 读无 BOM 会按 GBK 解码，中文乱码 → 引号错位
   → 报一堆**假的**语法错。改完脚本必查 BOM。
2. **`Invoke-RestMethod` 发中文 JSON 会变成 `?`。** 不报错，静默损坏。
   调 GitHub API 一律用 **Node 的 `fetch`**。
3. **GitHub 标签与发行版硬绑定。** 删标签会让发行版降级成草稿（**附件不丢**），
   要用 API `PATCH tag_name` 挂回去。
4. **`sed` 不支持 `\xEF`**，剥 BOM 要用 `perl`。
5. **`filter-branch -- --all` 会重写你自己建的备份分支**，别指望本地分支当备份。
6. **Gradle 的 `file()` 在 `android {}` 块里相对模块目录解析**，
   找 keystore 必须用 `rootProject.file()`，否则差一层 → 签名静默失效。
7. **Android 11+ scoped storage**：adb shell 不能读写 `Android/data/`，
   模型必须放 `/data/local/tmp/laya-model.onnx`。

**另外两条**：
- PowerShell 写文件默认不写 BOM —— 但 `.ps1` 需要 BOM。搞混就会出第 1 条那种问题。
- 用 PowerShell 的 `-replace` 批量改代码文件，会把模板字符串里的反引号吃掉。
  **批量替换只适合纯文本，改代码请用编辑器逐处改。**

---

# 第八节 · 关键文件在哪

```
D:\laya\
├── README.md            用户面向的说明（含"这是个玩具"的免责声明）
├── HANDOFF.md           本文件
├── BUILD.md             编译/签名/踩坑记录（14 条，最实用）
├── PROJECT.md           设计决策与理由
├── LICENSE              MIT（注明模型权重是 Apache-2.0）
├── fetch-model.ps1      【clone 后先跑】下载分词器 + 262MB 模型
├── build.ps1            一键编译（优先用工程自带 gradlew）
├── install-to-phone.ps1 一键装真机（自动识别 ABI，可带模型）
├── push-model.ps1       单独传模型到设备
├── push-to-github.ps1   推送（内置密钥/凭证检查）
├── reference\           ⚠️ 不在仓库里，由 fetch-model.ps1 生成
├── screenshots\         22 张实测截图
├── server\              PC 侧代码
│   ├── mcp.mjs          ★ MCP 入口（stdio；decide / list_backends）
│   ├── onnx-backend.mjs ★ int4 ONNX 推理，MCP 的真实模型走这里
│   ├── laya-core.mjs    ★ 校验 / 排序 / 决策组装（纯函数）
│   ├── inference.mjs    MockBackend（MCP 演示模式用）+ 旧的 RealBackend
│   ├── test\core.test.mjs   ✅ 测 laya-core
│   ├── test\mcp.test.mjs    ✅ 真的拉起 MCP 服务自测
│   └── tools\           15 个验证/分析工具
└── app\app\src\
    ├── main\java\com\laya\decide\
    │   ├── agent\LayaAgent.kt        端侧 ONNX 推理（复刻参考实现）
    │   ├── agent\SequenceBuilder.kt  序列构造
    │   ├── agent\ModelFiles.kt       模型查找/下载
    │   ├── tokenizer\Tokenizer.kt    ByteLevel BPE 的 Kotlin 移植
    │   ├── backend\InferenceBackend.kt  后端抽象（端侧 / 演示两种）
    │   ├── mcp\
    │   │   ├── McpHttpServer.kt          MCP HTTP 服务（JSON-RPC / 工具）
    │   │   ├── McpForegroundService.kt   通知保活 + 通知里的「停止」
    │   │   ├── McpPrefs.kt / McpNetwork.kt
    │   └── core\DecisionEngine.kt    打分→排序逻辑 + 字数上限常量
    └── test\                         50 个单测（含逐 id 夹具比对）
```

---

# 第九节 · 这个 App 的设计要点（改代码前必须知道）

**用 `score` 打分排序，不用 `choice` 多选一。**
`choice` 找的是"语义最相近的标签"，决策要的是"哪一个最有利"，
两者在模型眼里不是一回事。详见 `PROJECT.md` 第一节。

**运行链路**（与参考实现逐字节对齐）：

```
NFC 规范化 → GPT-2 正则预切分 → UTF-8 字节映射 → BPE → 查词表
  → [CLS] type question: ins [SEP] [MASK] opt0 [MASK] opt1 … [SEP] state [SEP]
  → ONNX 前向 → logits 除以温度 → softmax → 期望分 Σ i·p(i) → 排序
```

**改了分词器 / 序列构造 / 提问措辞，必须重新生成夹具并重跑测试**：

```powershell
cd D:\laya\server
node tools/gen-tokenizer-fixture.mjs
node tools/gen-sequence-fixture.mjs
cd ..\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" testDebugUnitTest
```

---

# 第十节 · 怎么用模拟器测试（操作手册）

以下命令都在 `D:\laya` 下、PowerShell 5.1 里实测过。

## 1. 启动模拟器

```powershell
& C:\Android\emulator\emulator.exe -avd dsh-test30 -no-snapshot -no-audio -no-boot-anim -gpu guest
```

- **必须 `-gpu guest`**。`-gpu swiftshader_indirect` 会因缺 `opengl32sw.dll` 直接崩，
  `-gpu auto` 时好时坏。
- 加 `-no-window` 也可以，`adb shell screencap` 照常工作，适合自动化。
- 想手动操作就去掉 `-no-window`，窗口会出现在桌面上。

## 2. 等它启动完

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
$deadline = (Get-Date).AddMinutes(5)
while((Get-Date) -lt $deadline){
  if(((& $adb shell getprop sys.boot_completed 2>&1) -join '').Trim() -eq '1'){ break }
  Start-Sleep -Seconds 6
}
& $adb devices     # 应显示 emulator-5554  device
```

## 3. 编译 + 装包（注意架构！）

**模拟器是 x86_64，必须装 x86_64 的包**，arm64 的装上去跑不起来。

```powershell
cd D:\laya\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" assembleDebug

& "$adb" install -r "D:\laya\app\app\build\outputs\apk\debug\app-x86_64-debug.apk"
```

签名冲突（之前装过 release）时先 `adb uninstall com.laya.decide`。

## 4. 把模型推到设备

```powershell
& $adb push "D:\laya\reference\models\model_int4.onnx" /data/local/tmp/laya-model.onnx
```

或直接跑 `.\push-model.ps1`（会自动找模型、校验 md5、幂等跳过）。

App 按这个顺序找模型：
1. `/sdcard/Android/data/com.laya.decide/files/models/model_int4.onnx`
2. `/data/data/com.laya.decide/files/models/model_int4.onnx`
3. `/data/local/tmp/laya-model.onnx` ← adb 部署落点

## 5. 驱动 App（不用手点）

**只有 debug 包能用这招**（`BuildConfig.DEBUG` 保护，release 里已关闭）。

```powershell
& $adb shell am start -S -n com.laya.decide/.MainActivity `
  --es situation "周末想出去玩，预算500，两个人，不想开车太远。" `
  --es options "本市周边爬山\|去隔壁城市逛吃\|露营野餐" `
  --es mode local `
  --ez decide true
```

| 参数 | 说明 |
|---|---|
| `situation` | 背景 |
| `options` | 选项，用 `\|` 分隔 —— **竖线必须转义**，否则被设备端 shell 当管道 |
| `mode` | `demo` / `local`（`http` 已随 PC 服务模式删除） |
| `decide` | `true` 则启动后自动算一次 |

中文参数可以直接传，实测没问题。

## 6. 截图

```powershell
& $adb shell screencap -p /sdcard/s.png
& $adb pull /sdcard/s.png D:\laya\screenshots\xx.png
```

## 7. 滚动（结果区常在屏幕外）

```powershell
& $adb shell input swipe 540 1600 540 400 200    # 上滑
& $adb shell input swipe 540 400 540 1600 200    # 下滑
& $adb shell input keyevent 3                    # HOME
& $adb shell input keyevent 4                    # 返回
```

## 8. 看界面文本（比截图可靠）

```powershell
& $adb shell uiautomator dump /sdcard/ui.xml
& $adb pull /sdcard/ui.xml "$env:TEMP\ui.xml"
[xml]$x = Get-Content "$env:TEMP\ui.xml" -Encoding UTF8
$x.SelectNodes("//node[@text!='']") | ForEach-Object { $_.text }
```

> ⚠️ **只返回可见节点**。结果区在屏幕外时不会出现 —— 先滚动再 dump，
> 否则会误判成"结果没渲染出来"（我踩过这个坑，白查了半天）。

## 9. 看日志

```powershell
# 只看关键行
& $adb logcat -s LayaModel:* AndroidRuntime:E

# 清空后重跑
& $adb logcat -c
```

`LayaModel` 这个 tag 会打：模型路径、大小、堆上限、ONNX 版本、加载耗时、
以及失败时的完整异常。

## 10. 关闭

```powershell
& $adb emu kill
Start-Sleep -Seconds 6
Get-Process emulator,qemu-system-x86_64 -EA SilentlyContinue | Stop-Process -Force
```

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| 启动就退出，日志有 `Failed to load opengl32sw` | 用了 `-gpu swiftshader_indirect`，换 `-gpu guest` |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | 装错架构，模拟器要 x86_64 包 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名冲突，先 `adb uninstall` |
| 结果区不显示 | 可能在屏幕外，先 swipe 再 uiautomator dump |
| 提示"还没下载模型" | 模型没推到 `/data/local/tmp/`，或大小 < 250MB（没下完） |
| 推理很慢 | 模拟器是软件渲染 + CPU 推理，真机会快很多 |

---

# 第十一节 · 怎么上传到 GitHub（操作手册）

## 认证：走 SSH，不要走 HTTPS

这台机器 `github.com` 的 HTTPS 被 SNI 阻断（TCP 能连、HTTP 超时），
`git push` 走 https 会报 `Recv failure: Connection was reset`。
**但 `api.github.com` 是通的**，所以很容易误判成"没权限"。

已经配好了：

```powershell
cd D:\laya
git remote -v      # 应该是 git@github.com:woaiys3/laya-decide.git
ssh -T git@github.com   # 应输出 Hi woaiys3! You've successfully authenticated
```

- 密钥 `~/.ssh/id_ed25519`，已注册（key id `165273918`）
- `~/.ssh/config` 里 `github.com` 走 22 端口；22 被封时用 `ssh.github.com:443`

## 推送流程

```powershell
cd D:\laya
git fetch origin                      # ★ 先拉！远程可能有人动过
git log --oneline main..origin/main    # 看有没有别人的提交
git add -A
git status --short                     # 确认没有密钥/大文件
git commit -m "..."                    # 建议用文件：git commit -F
git push
```

**不要强推。** 上一个人推送时发现远程有 2 个不是自己提交的 commit，
靠 rebase 合过去才没覆盖。遇到 `rejected / fetch first` 就：

```powershell
git rebase origin/main    # 或 git pull --rebase
git push
```

## 提交前必须检查的

```powershell
$tracked = git ls-files
# 这些绝不能出现
$tracked | Select-String '\.jks$|keystore\.properties|local\.properties|\.onnx$|^reference/|^app/app/build/'
```

`.gitignore` 已覆盖 `*.jks` / `keystore.properties` / `local.properties` /
`reference/` / `build/` / `node_modules/`。

## 调 GitHub API（取 token，别硬编码）

凭据在 Windows 凭据管理器里，用 `git credential fill` 取：

```js
import { execFileSync } from 'node:child_process';
const out = execFileSync('C:/Program Files/Git/cmd/git.exe', ['credential', 'fill'], {
  input: 'protocol=https\nhost=github.com\n\n', encoding: 'utf8',
});
const token = out.match(/^password=(.+)$/m)[1].trim();
```

> ⚠️ **发中文 JSON 必须用 Node 的 `fetch`，不要用 PowerShell 的 `Invoke-RestMethod`。**
> 后者会把中文替换成 `?` 且不报错（仓库简介就被这么毁过一次）。
> 现成工具：`server/tools/fix-repo-meta.mjs`、`server/tools/audit-encoding.mjs`。

## 建发行版 + 传 APK

APK 放 Release，**不要进仓库**（不占仓库体积，用户下载也不用登录）。

```powershell
# 1) 先编译
cd D:\laya\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" assembleDebug assembleRelease

# 2) 生成校验和
$dir = "D:\laya\app\app\build\outputs\apk\release"
Get-ChildItem $dir -Filter *.apk | ForEach-Object {
  "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)"
} | Out-File "$dir\SHA256SUMS.txt" -Encoding ascii
```

然后用 API 建 release 并上传（用 Node fetch，中文不会坏）：

```
POST https://api.github.com/repos/woaiys3/laya-decide/releases
     { tag_name, target_commitish:"main", name, body, draft:false }
  → 返回 id 和 upload_url

POST https://uploads.github.com/repos/woaiys3/laya-decide/releases/{id}/assets?name=xxx.apk
     --data-binary @文件      (PowerShell 用 Invoke-RestMethod -InFile)
```

已存在的 release 可以用 `POST .../releases/{id}/assets?name=` 补传附件。

## ⚠️ 标签与发行版是硬绑定的

删掉远程 tag 会让挂在它上面的 release **降级成"未打标签的草稿"**
（页面变成 `releases/tag/untagged-xxxxx`）。**附件不会丢。**

修法（PATCH 回去）：

```
PATCH https://api.github.com/repos/woaiys3/laya-decide/releases/{id}
      { tag_name: "v1.0.0", draft: false }
```

所以顺序是：**记下 release id → 重写历史 → 重建 tag → PATCH 挂回去**，
不要"删了重做"（那要重传几百 MB）。

## 改了提交信息之后（重写历史）

```powershell
# 剥 BOM 只能用 perl，sed 不认 \xEF
git filter-branch -f --msg-filter "perl -pe 's/^\xEF\xBB\xBF//'" -- --all

# ★ 必须核对 tree hash —— 相同才证明文件内容零改动
git log --format="%h %T %s"

# 原状态留在 refs/original，确认无误后再删
git for-each-ref refs/original
git update-ref -d refs/original/refs/heads/main
```

⚠️ `filter-branch -- --all` 会**连你自己的备份分支一起重写**，
所以别指望本地分支当备份，要靠 `refs/original/`。

## 提交信息里别带 BOM

```powershell
# ❌ 会带 BOM，GitHub 上标题前多个乱码块
$msg | Out-File -Encoding utf8 $tmp

# ✅ 用这个
[System.IO.File]::WriteAllText($tmp, $msg, (New-Object System.Text.UTF8Encoding($false)))
git commit -F $tmp
```

---


# 第十二节 · 被搁置的事（别主动捡起来）

- **微调**：查过，这台机器跑不了（无 CUDA、可用内存 4GB、没有 Python 环境），
  官方 notebook 要 2×T4。用户最后说"算了吧"。
- **现成微调模型**：社区 144 个 Laya 相关仓库里**没有**能解决
  "帮人排序人生选择"的现成模型。唯一真正微调过的官方 checkpoint
  训的是发票/客服/安全事件，域外无收益。
- **换更小的模型**：`oscar-1` 系列（35MB 起）用 typed-decisions 数据集训练，
  但架构不同（普通 encoder），换上去要重写整个推理层。不建议。

如果用户哪天又提起微调，先问他要**标注数据**——没有"用户真实选择"作为标签，
微调等于让模型学它自己。
