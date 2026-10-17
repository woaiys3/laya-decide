# 交接文档

> 写给下一个接手的人（或下一个上下文窗口里的我）。
> 最后更新：2026-10-03，提交 `73af891`。
>
> **先读第一节，那是你要做的两件事。** 第二节之后是背景资料，遇到具体问题时再查。

---

# 第一节 · 你的任务

## 任务 1：删掉「PC 服务」模式

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

## 任务 2：加一个本地 MCP 服务，让 AI 能用「抉择」

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

## 任务 3：背景和选项"能写的字太少"的反馈

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
- typed-decisions 训的是发票/客服/安全事件，域外无收益（见第十节）
- multilingual 是英文弱、多语言强，中文会更差

**除非 B 被证伪，否则不要走这条路。**

### 建议的执行顺序

1. **先做 A**（半小时，立刻消除欺骗性）
2. **再试 B**（一小时，用实测决定 1024 能不能用）
3. B 只要不退化，就把 A 的 300 字限制按 B 的实测结果重新放宽

### 验收

- 背景输入到上限时**打不进去**（而不是打进去被丢）
- 界面上能看到当前字数 / 上限
- `node server/tools/measure-limits.mjs` 输出的"实际能进模型"数字，与 UI 上限一致
  （允许 UI 上限略小于模型上限，但**绝不能大**）

---


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
| 单元测试 | ✅ Android 50 个 + PC 服务 21 个，全过 |
| 三种后端 | ✅ 端侧 / PC 服务 / 演示模式 均实测（PC 服务待删） |
| Release 签名 | ✅ apksigner 验签通过 |
| ABI 拆包 | ✅ 133MB → 34.9MB |
| GitHub 仓库 | ✅ 138 个 tree 条目，密钥确认未上传 |
| Release 附件 | ✅ 5 个（arm64 / armv7 / universal / x86_64 / SHA256SUMS） |

**版本**：`versionCode 1` / `versionName "1.0"`

仓库：https://github.com/woaiys3/laya-decide
发行版：https://github.com/woaiys3/laya-decide/releases/tag/v1.0.0
本地：`D:\laya`（桌面副本：`C:\Users\Administrator\Desktop\Laya决策助手\`）

> ⚠️ 仓库可能被别人动过。我最后一次推送时发现远程有 2 个不是我提交的 commit
> （`docs(README):` 风格，加了又删了 dshaa.xin 的链接，净变化为零）。
> **推之前先 `git fetch`，不要强推。**

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
│   ├── laya-core.mjs    ★ 决策逻辑，MCP 要复用
│   ├── inference.mjs    ★ ONNX 推理，MCP 要复用
│   ├── server.mjs       ❌ 任务 1 要删（HTTP 接口）
│   ├── test\core.test.mjs  ✅ 保留（测 laya-core）
│   └── tools\           12 个验证/分析工具
└── app\app\src\
    ├── main\java\com\laya\decide\
    │   ├── agent\LayaAgent.kt        端侧 ONNX 推理（复刻参考实现）
    │   ├── agent\SequenceBuilder.kt  序列构造
    │   ├── agent\ModelFiles.kt       模型查找/下载
    │   ├── tokenizer\Tokenizer.kt    ByteLevel BPE 的 Kotlin 移植
    │   ├── backend\InferenceBackend.kt  ★ 任务 1 要改
    │   └── core\DecisionEngine.kt    打分→排序逻辑
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

# 第十节 · 被搁置的事（别主动捡起来）

- **微调**：查过，这台机器跑不了（无 CUDA、可用内存 4GB、没有 Python 环境），
  官方 notebook 要 2×T4。用户最后说"算了吧"。
- **现成微调模型**：社区 144 个 Laya 相关仓库里**没有**能解决
  "帮人排序人生选择"的现成模型。唯一真正微调过的官方 checkpoint
  训的是发票/客服/安全事件，域外无收益。
- **换更小的模型**：`oscar-1` 系列（35MB 起）用 typed-decisions 数据集训练，
  但架构不同（普通 encoder），换上去要重写整个推理层。不建议。

如果用户哪天又提起微调，先问他要**标注数据**——没有"用户真实选择"作为标签，
微调等于让模型学它自己。
