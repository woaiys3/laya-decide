# Laya 决策助手 —— 编译与安装

## 环境（本机已验证可用）

| 项 | 值 |
|---|---|
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`（JAVA_HOME 已设） |
| Android SDK | `C:\Android`（platform 33/34、build-tools 34/35） |
| Gradle | `D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat`（本机已有完整发行版） |
| 模拟器 | AVD `dsh-test30`（Android 11 / API 30 / x86_64） |
| adb | `C:\Android\platform-tools\adb.exe` |

工程里钉住 **AGP 8.5.2 + Kotlin 2.0.21**，这两个版本本机 Gradle 缓存里已有，首次构建下载量最小。

---

## 一条命令搞定

```powershell
D:\laya\build.ps1              # 编译 debug + release，并跑单测
D:\laya\build.ps1 -Install     # 编译后自动装到已连接的设备/模拟器
D:\laya\build.ps1 -Release     # 只出签名 release 包
```

---

## 手动步骤

### 1. 编译

```powershell
cd D:\laya\app
& "D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat" assembleDebug assembleRelease testDebugUnitTest
```

也可以用工程自带的 wrapper（需要能访问 `mirrors.cloud.tencent.com` 下载分发）：

```powershell
cd D:\laya\app
.\gradlew.bat assembleDebug
```

### 2. 产物位置

ONNX Runtime 的原生库很大，所以**按 ABI 拆包** —— 全塞一个包里会是 133MB。

| 包 | 路径 | 大小 | 给谁 |
|---|---|---|---|
| **arm64-v8a release** | `app\app\build\outputs\apk\release\app-arm64-v8a-release.apk` | 35 MB | **现代手机，装这个** |
| armeabi-v7a release | `...\release\app-armeabi-v7a-release.apk` | 26 MB | 老手机 |
| x86_64 release | `...\release\app-x86_64-release.apk` | 41 MB | 模拟器 |
| universal release | `...\release\app-universal-release.apk` | 132 MB | 不确定架构时 |

debug 包同理（`...\apk\debug\app-arm64-v8a-debug.apk`）。debug 包大一点，因为带调试符号且内置了调试入口。

### 3. 安装

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
& $adb install -r D:\laya\app\app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

或者直接 `D:\laya\build.ps1 -Install`，它会自动挑 arm64 release 包。

如果手机上已装过 debug 版、签名不同，先卸载：

```powershell
& $adb uninstall com.laya.decide
```

### 4. 验签

```powershell
& "C:\Android\build-tools\35.0.0\apksigner.bat" verify --print-certs `
  D:\laya\app\app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

### 5. 装模型（重要）

APK 里**没有模型**（262MB，放不进去）。装完 App 是空的，需要：

- **方式 A**：App 内 设置 → 端侧模型 → 「下载模型」（走 hf-mirror.com 镜像，无需账号）
- **方式 B**：`D:\laya\push-model.ps1` 从电脑传（快，且适合镜像不通的情况）

App 会自动在这些位置找模型：

1. `/sdcard/Android/data/com.laya.decide/files/models/model_int4.onnx` — App 自己下载的落点
2. `/data/data/com.laya.decide/files/models/model_int4.onnx`
3. `/data/local/tmp/laya-model.onnx` — adb 部署落点（`push-model.ps1` 用这个）

> `/data/local/tmp` 是 Android 11+ 下唯一同时满足「adb shell 可写」和「应用可读」的位置。
> 直接 push 到 `Android/data/` 会静默失败 —— scoped storage 不允许，而且 shell 连读回来校验都做不到。

---

## 模拟器

```powershell
$env:ANDROID_HOME = "C:\Android"
& C:\Android\emulator\emulator.exe -avd dsh-test30 -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect
```

用 `-no-window` 也能正常截图（`adb shell screencap` 不受影响），所以自动化验证不必把窗口弹出来。

**模拟器里连 PC 服务要用 `10.0.2.2`，不是 `127.0.0.1`** —— 后者的 `localhost` 是模拟器自己。

模拟器上的性能参考（x86_64、软件渲染，属于最慢的情况）：

| 项 | 耗时 |
|---|---|
| 模型加载（首次） | 约 6 秒 |
| 单次决策（4-5 个选项） | 数秒 |
| 应用堆上限 | 192 MB（模型 262MB 走原生内存，不受此限制） |

真机上会明显更快。

---

## 用命令行驱动 App（调试用）

debug 包里内置了一个 intent 入口，可以直接把中文情境和选项灌进去，省得在模拟器里手打。
**release 包里这个入口是关闭的**（受 `BuildConfig.DEBUG` 保护，已实测验证）。

```powershell
$adb = "C:\Android\platform-tools\adb.exe"
& $adb shell am start -S -n com.laya.decide/.MainActivity `
  --es situation "我拿到一个创业公司offer，薪资降30%，但有期权" `
  --es options "接受这个offer\|留在现在的大厂\|继续面其他公司" `
  --es mode local `
  --ez decide true
```

| 参数 | 说明 |
|---|---|
| `situation` | 情境描述 |
| `options` | 选项，用 `\|` 分隔（竖线在设备端 shell 里有含义，必须转义） |
| `mode` | `demo` / `http` / `local` |
| `server` | PC 服务地址（仅 http 模式用） |
| `decide` | 传 `true` 则启动后自动触发一次决策 |

这个入口只在 `BuildConfig.DEBUG` 为真时生效，**release 包里是关掉的**（已验证）。

> 用途不只是测试：以后想批量对比不同的提问写法、试不同档位描述，都能用命令行跑，不用手点。

---

## 换成你自己的签名

当前的 `app/laya-decide.jks` 是演示证书，口令是弱口令。上架前必须换：

```powershell
keytool -genkeypair -v -keystore my-release.jks -storetype JKS `
  -alias my-alias -keyalg RSA -keysize 2048 -validity 10000
```

然后改 `app/keystore.properties`。也可以用环境变量覆盖（CI 场景）：

```powershell
$env:LAYA_KEYSTORE        = "my-release.jks"
$env:LAYA_KEYSTORE_PASSWORD = "..."
$env:LAYA_KEY_ALIAS       = "my-alias"
$env:LAYA_KEY_PASSWORD    = "..."
```

构建时会打印一行 `[签名] keystore 配置: ...`，用它确认签名到底有没有生效 —— 没生效的话产物会叫 `app-release-unsigned.apk`。

⚠️ **keystore 丢了就再也无法更新同一个 App**（包名 + 签名必须一致）。备份好，别提交到版本库（`.gitignore` 已经排除了）。

---

## 踩坑记录（都已解决，改脚本前先看）

**1. `build.ps1` 必须带 UTF-8 BOM。**

本机只有 Windows PowerShell 5.1（没有 pwsh 7）。**5.1 读没有 BOM 的 `.ps1` 时会按 ANSI/GBK 解码**，脚本里的中文注释和字符串会变成乱码，引号随之错位，报的却是一堆 `Unexpected token '}'`、`Missing closing ')'` —— 看着像语法错，其实是编码错。

如果你用编辑器改了这个脚本，**务必确认它保存为「UTF-8 带 BOM」**。检查方法：

```powershell
$b = [System.IO.File]::ReadAllBytes('D:\laya\build.ps1')
'{0:X2} {1:X2} {2:X2}' -f $b[0], $b[1], $b[2]   # 应该是 EF BB BF
```

**2. PowerShell 5.1 的语法限制**（脚本已按这些约束写）：

- 多行 `if` 表达式不能直接赋值，得加括号或用 `if/else` 语句块
- 字符串里的双引号不能靠反引号转义（`` `" ``），得用单引号包外层
- 没有三元运算符

**3. 用 curl 测服务时不要在 PowerShell 里拼 JSON。**

引号转义会让 `curl.exe` 收到坏 JSON。稳妥做法是写成字节数组再 `--data-binary "@临时文件"`。这不是服务的问题 —— 服务端正确地返回了 400 而不是崩溃。

**4. Gradle 的 `file()` 在 `android {}` 块里是相对模块目录解析的。**

`app/build.gradle.kts` 里解析 keystore 必须用 `rootProject.file()`，否则会找成 `app/app/xxx.jks`，差一层，表现为「签名静默失效、产物叫 `app-release-unsigned.apk`」。构建时会打印一行 `[签名] keystore 配置: ...`，看那行就知道有没有生效。

**5. 模拟器用 `-no-window` 也能截图。**

`-no-window` 下 `adb shell screencap` 依然正常，所以自动化验证不需要把窗口弹出来。

**6. 这台机器上 `github.com` 的 HTTPS 被阻断，必须用 SSH。**

| 域名 | 结果 |
|---|---|
| `api.github.com` | ✅ 通（HTTP 200） |
| `codeload.github.com` | ✅ 通 |
| `raw.githubusercontent.com` | ✅ 通 |
| **`github.com`** | ❌ **TCP 能连、HTTP 超时** —— 典型的 SNI 阻断 |

所以 `git push` 走 HTTPS 会报 `Recv failure: Connection was reset`。

**解法：改用 SSH。** SSH 不走 SNI，能绕过去：

```powershell
# 生成密钥（如果还没有）
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\id_ed25519" -N '""'

# 把公钥加到 GitHub：Settings -> SSH and GPG keys -> New SSH key
Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub"

# 切换 remote 并推送
git remote set-url origin git@github.com:用户名/仓库.git
git push -u origin main
```

如果 22 端口也被封，GitHub 有官方备用通道（443 端口），在 `~/.ssh/config` 里配：

```
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/id_ed25519
```

**7. 模拟器启动必须用 `-gpu guest`（在这台机器上）。**

`-gpu swiftshader_indirect` 会去找 `opengl32sw.dll`，这台机器的模拟器目录里没有，直接崩。
`-gpu auto` 时好时坏（有时也会走到 swiftshader 分支而失败）。
**`-gpu guest` 完全不走宿主机 OpenGL，最稳**：

```powershell
& C:\Android\emulator\emulator.exe -avd dsh-test30 -no-snapshot -no-audio -no-boot-anim -gpu guest
```

**8. Windows PowerShell 5.1 的参数名不能用 `-Debug`。**

那是通用参数（common parameter），和 `[CmdletBinding()]` 一起用会直接报
`A parameter with the name 'Debug' was defined multiple times`。改用 `-DebugBuild` 之类。

---

## 换一台机器要改什么

| 文件 | 改什么 |
|---|---|
| `app\local.properties` | `sdk.dir` 指向新机器的 Android SDK |
| `build.ps1` 顶部 | `$Gradle` / `$Adb` / `$SdkRoot` / `$Avd` |

