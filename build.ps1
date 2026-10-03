# 编译 / 安装 Laya 决策助手
#
#   .\build.ps1                 编译 debug + release，并跑单测
#   .\build.ps1 -Install        编译后装到已连接的设备
#   .\build.ps1 -Release        只出签名 release 包
#   .\build.ps1 -Emulator       顺手把模拟器起起来
#
# 目标是兼容 Windows PowerShell 5.1（本机没有 pwsh 7）。
# 5.1 的老脾气：多行 if 表达式必须加括号、字符串里的双引号不能靠反引号转义、
# 三元运算符不存在 —— 下面都按这些约束写。
#
# 详见 BUILD.md

[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Release,
    [switch]$Emulator
)

$ErrorActionPreference = 'Stop'

# ── 机器相关路径 ─────────────────────────────────────────────────────────
#
# 这几个是本机路径，换机器要改。也可以用环境变量覆盖，免得到处改脚本：
#   $env:LAYA_GRADLE / $env:ANDROID_HOME / $env:LAYA_ADB / $env:LAYA_AVD
#
# 仓库本身不含模型与构建产物，clone 之后先跑 fetch-model.ps1 拿模型。
$Root    = $PSScriptRoot
$AppDir  = Join-Path $Root 'app'

# Gradle 的挑选顺序：
#   1) 环境变量 LAYA_GRADLE 指定的
#   2) 工程自带的 wrapper（最通用，但首次要联网下 Gradle 分发）
#   3) 本机已装的完整发行版
$Gradle = $env:LAYA_GRADLE
if (-not $Gradle) {
    $wrapper = Join-Path $AppDir 'gradlew.bat'
    if (Test-Path $wrapper) {
        $Gradle = $wrapper
    } else {
        $Gradle = 'D:\dsh\aibendi\.tools\gradle-8.9\bin\gradle.bat'
    }
}

$SdkRoot = $env:ANDROID_HOME
if (-not $SdkRoot) { $SdkRoot = 'C:\Android' }

$Adb = $env:LAYA_ADB
if (-not $Adb) { $Adb = Join-Path $SdkRoot 'platform-tools\adb.exe' }

$Avd = $env:LAYA_AVD
if (-not $Avd) { $Avd = 'dsh-test30' }

function Fail($msg) {
    Write-Host ''
    Write-Host ('  x ' + $msg) -ForegroundColor Red
    exit 1
}

# --- 前置检查 ------------------------------------------------------------

if (-not (Test-Path $Gradle)) {
    Fail ('找不到 Gradle: ' + $Gradle + "`n  改这个脚本顶部的 `$Gradle 路径，或装一个 Gradle 8.7+。")
}
if (-not (Test-Path $SdkRoot)) {
    Fail ('找不到 Android SDK: ' + $SdkRoot + "`n  改脚本顶部的 `$SdkRoot。")
}
if (-not $env:JAVA_HOME) {
    Write-Host '  ! JAVA_HOME 没设置，Gradle 可能找不到 JDK' -ForegroundColor Yellow
}

$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

# --- 起模拟器（可选） ----------------------------------------------------

if ($Emulator) {
    Write-Host ''
    Write-Host ('  > 启动模拟器 ' + $Avd + ' ...') -ForegroundColor Cyan
    $emuArgs = @('-avd', $Avd, '-no-snapshot', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect')
    Start-Process -FilePath (Join-Path $SdkRoot 'emulator\emulator.exe') -ArgumentList $emuArgs -WindowStyle Minimized
    Write-Host '    等它起来（首次约 1 分钟）...'
}

# --- 决定构建哪些目标 ----------------------------------------------------

if ($Release) {
    $tasks = @('assembleRelease')
} else {
    $tasks = @('assembleDebug', 'assembleRelease', 'testDebugUnitTest')
}

Write-Host ''
Write-Host ('  > 编译中: ' + ($tasks -join ', ')) -ForegroundColor Cyan
Write-Host ''

Push-Location $AppDir
$code = 0
try {
    & $Gradle @tasks '--console=plain'
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Fail ('构建失败（Gradle 退出码 ' + $code + '）')
}

# --- 汇报产物 ------------------------------------------------------------

Write-Host ''
Write-Host '  > 产物' -ForegroundColor Cyan

$apkRoot = Join-Path $AppDir 'app\build\outputs\apk'
$apks = @(Get-ChildItem $apkRoot -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    Sort-Object { $_.Directory.Name }, Name)

# 按 ABI 拆包后产物有多个，挑一个"给手机用"的作为安装目标。
# 优先级：release arm64（现代手机）> 任意 release > debug arm64 > 任意 debug。
function Pick-Apk($list, $pattern) {
    return ($list | Where-Object { $_.Name -like $pattern } | Select-Object -First 1)
}

$signedApk = Pick-Apk $apks 'app-arm64-v8a-release.apk'
if (-not $signedApk) { $signedApk = Pick-Apk $apks 'app-*-release.apk' }

foreach ($a in $apks) {
    $mb = [math]::Round($a.Length / 1MB, 2)
    $tag = $a.Directory.Name
    $mark = ''
    if ($signedApk -and ($a.FullName -eq $signedApk.FullName)) { $mark = '  <- 装机首选' }
    Write-Host ('    {0,-34} {1,7} MB  {2}{3}' -f $a.Name, $mb, $tag, $mark)
}

$tr = Join-Path $AppDir 'app\build\test-results\testDebugUnitTest'
if (Test-Path $tr) {
    # 每个测试类一个 XML，必须全部累加 —— 只读第一个会少算（曾经显示 7 而不是 50）。
    $nTests = 0; $nSkip = 0; $nFail = 0; $nErr = 0
    foreach ($xml in @(Get-ChildItem $tr -Filter *.xml -ErrorAction SilentlyContinue)) {
        $raw = Get-Content $xml.FullName -Raw
        if ($raw -match 'tests="(\d+)"\s+skipped="(\d+)"\s+failures="(\d+)"\s+errors="(\d+)"') {
            $nTests += [int]$Matches[1]
            $nSkip  += [int]$Matches[2]
            $nFail  += [int]$Matches[3]
            $nErr   += [int]$Matches[4]
        }
    }
    if ($nTests -gt 0) {
        $color = 'Red'
        if (($nFail -eq 0) -and ($nErr -eq 0)) { $color = 'Green' }
        Write-Host ''
        Write-Host ('  > 单测: ' + $nTests + ' 个用例，失败 ' + $nFail + '，错误 ' + $nErr + '，跳过 ' + $nSkip) -ForegroundColor $color
    }
}

# --- 安装（可选） --------------------------------------------------------

if ($Install) {
    if (-not (Test-Path $Adb)) { Fail ('找不到 adb: ' + $Adb) }

    $target = $null
    if ($signedApk) {
        $target = $signedApk.FullName
    } else {
        $debugApk = Pick-Apk $apks 'app-arm64-v8a-debug.apk'
        if (-not $debugApk) { $debugApk = Pick-Apk $apks 'app-*-debug.apk' }
        if ($debugApk) { $target = $debugApk.FullName }
    }
    if (-not $target) { Fail '没找到可安装的 APK' }

    Write-Host ''
    Write-Host ('  > 安装 ' + [System.IO.Path]::GetFileName($target)) -ForegroundColor Cyan
    & $Adb install -r $target
    if ($LASTEXITCODE -ne 0) {
        Write-Host '    安装失败，可能是签名冲突。先卸载再试：' -ForegroundColor Yellow
        Write-Host ('      & "' + $Adb + '" uninstall com.laya.decide')
        exit 1
    }
    Write-Host ''
    Write-Host '  > 启动' -ForegroundColor Cyan
    & $Adb shell am start -n com.laya.decide/.MainActivity
}

Write-Host ''
Write-Host '  OK 完成' -ForegroundColor Green
Write-Host ''
