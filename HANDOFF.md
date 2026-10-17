# 交接文档

> 写给下一个接手的人（或下一个上下文窗口里的我）。
> 最后一轮对话在 2026-10-03。当前提交 `f20bf4e`，`main` 与 `origin/main` 同步。

---

## 一、这个项目是什么

**「抉择」**——一个 Android App：导入情境 → 列几个选项 → 让**跑在手机上**的
Laya 决策模型给选项打分排序。

- 完全离线，不需要账号、不需要服务器
- 仓库：https://github.com/woaiys3/laya-decide
- 发行版：https://github.com/woaiys3/laya-decide/releases/tag/v1.0.0
- 本地：`D:\laya`
- 桌面备份：`C:\Users\Administrator\Desktop\Laya决策助手\`

---

## 二、⚠️ 用户明确定过的态度（最重要，先看这个）

用户最后把定位改成了**「就是个玩具」**，这几条必须遵守：

1. **不要再追求准确率。** 用户原话："反正也没有真人把这个软件当参与重大抉择，
   都是玩玩的，正确率低，戏剧性玩的话更好。"
   → 不要主动去做微调、不要加复杂功能、不要说"我可以让它更准"。

2. **不要打大动干戈。** 用户原话："没必要这么打大动干戈。"
   → 修一个问题就收工，不要顺手写验证脚本、不要做额外审计、不要起进程去
   长时间观察。上一个人（我）因为过度验证被明确批评过。

3. **不要影响机器上其他项目。** 用户原话："我的另一个项目也要用这个环境打包，
   你不要影响到它。"
   → `D:\dsh\aibendi` 是用户的另一个 Android 工程，共用同一套工具链。
   不要改它的任何文件，不要长时间占用 Gradle 守护进程/内存/端口。

4. **该说的实话要说。** 用户接受"这模型不准"，但前提是 App **诚实地**告诉他
   不准 —— 界面上「模型基本在猜」和「排序不可信」警告条是用户认可的设计，
   不要为了"好看"删掉。

---

## 三、当前状态

| 项 | 状态 |
|---|---|
| 端侧推理 | ✅ 跑通（Android 11 模拟器 x86_64，加载约 6 秒） |
| 与参考实现一致性 | ✅ 分词器 64 条、序列构造 8 场景，**逐 token id 一致** |
| 单元测试 | ✅ Android 50 个 + PC 服务 21 个，全过 |
| 三种后端 | ✅ 端侧 / PC 服务 / 演示模式 均实测 |
| Release 签名 | ✅ apksigner 验签通过 |
| ABI 拆包 | ✅ 133MB → 34.9MB |
| GitHub 仓库 | ✅ 138 个 tree 条目，密钥确认未上传 |
| Release 附件 | ✅ 5 个（arm64 / armv7 / universal / x86_64 / SHA256SUMS） |

**版本**：`versionCode 1` / `versionName "1.0"`

---

## 四、❗ 唯一未验证的高风险项

**arm64 真机的端侧推理从没跑过。** 手上只有 x86_64 模拟器。

ONNX Runtime 的 arm64 库在包里、签名正常，但真机首次运行是头一回。
Release 说明里已如实写明。如果用户报"闪退"或"卡在加载中"，
**第一件事是看这个日志**：

```
adb logcat -s LayaModel:* AndroidRuntime:E
```

---

## 五、环境事实（别重复探测）

| 项 | 值 |
|---|---|
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`（JAVA_HOME 已设） |
| Android SDK | `C:\Android`（platform 33/34，build-tools 34/35） |
| **Gradle** | `D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat`（借用用户的） |
| **严禁使用** | `-gpu swiftshader_indirect`（缺 opengl32sw.dll 会崩）；`-gpu auto` 不可靠 |
| **模拟器正确启动** | `-gpu guest`（唯一稳定） |
| AVD | `dsh-test30`（Android 11 / API 30 / x86_64） |
| adb | `C:\Android\platform-tools\adb.exe` |
| 本机 OS | Windows 10，**只有 PowerShell 5.1，没有 pwsh 7** |
| 网络 | `github.com` HTTPS 被 SNI 阻断（API 和 codeload 正常）；`huggingface.co` 不通，走 `hf-mirror.com` |
| 内存 | 16 GB，注意别抢占 |
| 显卡 | AMD R5 230（2013 年），无 CUDA |

**工具链一个字都没改过**：Gradle 目录文件时间全是 1980（zip 解压产物），
Android SDK 最新改动是 2026-08-29。`D:\dsh\aibendi` 的配置也是 8 月的。

---

## 六、GitHub 相关

- **认证走 SSH**，不是 HTTPS（HTTPS 被阻断）
  - 密钥：`~/.ssh/id_ed25519`，已注册到账号（key id `165273918`）
  - `~/.ssh/config` 里配了 `github.com` 走 22 端口、`github-ssh` 走 443 备用
  - remote：`git@github.com:woaiys3/laya-decide.git`
- **缓存凭据**：Windows 凭据管理器里有 `git:https://github.com`（用户名 `woaiys3`），
  可用 `git credential fill` 取，调 API 用
- **Token 泄露**：用户在对话里贴过一个 `ghp_...` token，我**从未使用**，
  也提醒过他去吊销。**不确定他吊销了没有**——如果之后有人看到异常操作，
  先查这件事

---

## 七、七个已踩过的坑（别再踩）

详细记录在 `BUILD.md` 的「踩坑记录」一节（14 条），这里只列最容易重复犯的：

1. **`.ps1` 必须 UTF-8 带 BOM。** PS 5.1 读无 BOM 会按 GBK 解码，
   中文乱码 → 引号错位 → 报一堆**假的**语法错。改完脚本必查 BOM。
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

---

## 八、关键文件在哪

```
D:\laya\
├── README.md            用户面向的说明（含"这是个玩具"的免责声明）
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
├── server\tools\        12 个验证/分析工具
└── app\app\src\
    ├── main\java\com\laya\decide\
    │   ├── agent\LayaAgent.kt        端侧 ONNX 推理（复刻参考实现）
    │   ├── agent\SequenceBuilder.kt  序列构造
    │   ├── agent\ModelFiles.kt       模型查找/下载
    │   ├── tokenizer\Tokenizer.kt    ByteLevel BPE 的 Kotlin 移植
    │   └── core\DecisionEngine.kt    打分→排序逻辑
    └── test\                         50 个单测（含逐 id 夹具比对）
```

---

## 九、这个 App 的设计要点（改代码前必须知道）

**用 `score` 打分排序，不用 `choice` 多选一。**
理由：`choice` 找的是"语义最相近的标签"，决策要的是"哪一个最有利"，
两者在模型眼里不是一回事。详见 `PROJECT.md` 第一节。

**运行链路**（与参考实现逐字节对齐）：
```
NFC 规范化 → GPT-2 正则预切分 → UTF-8 字节映射 → BPE → 查词表
  → [CLS] type question: ins [SEP] [MASK] opt0 [MASK] opt1 … [SEP] state [SEP]
  → ONNX 前向 → logits 除以温度 → softmax → 期望分 Σ i·p(i) → 排序
```

**改了分词器或序列构造或提问措辞，必须重新生成夹具并重跑测试**：
```
cd server
node tools/gen-tokenizer-fixture.mjs
node tools/gen-sequence-fixture.mjs
cd ../app && gradlew testDebugUnitTest
```

---

## 十、被搁置的事（别主动捡起来）

- **微调**：用户问过，查完发现这台机器跑不了（无 CUDA、可用内存 4GB、
  没有 Python 环境），官方 notebook 要 2×T4。用户最后说"算了吧"。
- **现成微调模型**：查过社区 144 个 Laya 相关仓库，**没有**能解决
  "帮人排序人生选择"的现成模型。唯一真正微调过的官方 checkpoint
  训的是发票/客服/安全事件，域外无收益。
- **端侧 ONNX 的替代模型**：`oscar-1` 系列（35MB 起）用 typed-decisions
  数据集训练，但架构不同（普通 encoder），换上去要重写整个推理层。

如果用户哪天又提起微调，先问他要**标注数据**——没有"用户真实选择"
作为标签，微调等于让模型学它自己。

---

## 十一、接下来可以做的（如果用户要）

按性价比：

1. **验证 arm64 真机**（最该做的，是唯一没验证的高风险项）
2. 给 App 加检查更新（读 GitHub Release 最新版本号）
3. 让用户自己弄个 keystore 换掉那个演示证书（口令是 `laya123456`，
   只为能发包，不适合正式上架）

**不要**主动做微调、不要加新功能、不要重构。
