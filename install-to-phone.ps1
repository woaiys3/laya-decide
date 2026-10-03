# 一键把 App 装到真机（自动识别架构，可选连模型一起传）
#
#   .\install-to-phone.ps1              装 release 版（日常用）
#   .\install-to-phone.ps1 -DebugBuild  装 debug 版（能看日志，排查用）
#   .\install-to-phone.ps1 -WithModel   顺便把 262MB 模型也传过去
#   .\install-to-phone.ps1 -DebugBuild -WithModel
#
# 前提：手机开「开发者选项 → USB 调试」，用数据线连上，手机上点「允许」。
#
# 注意：参数叫 -DebugBuild 而不是 -Debug —— 后者是 PowerShell 的通用参数，
# 跟 [CmdletBinding()] 一起用会直接报 "parameter defined multiple times"。
#
# ⚠️ 本文件必须保存为 UTF-8 带 BOM（Windows PowerShell 5.1 的要求）。

[CmdletBinding()]
param(
    [switch]$DebugBuild,
    [switch]$WithModel,
    [string]$Adb = 'C:\Android\platform-tools\adb.exe',
    [string]$Model = '',
    [string]$ApkRoot = ''
)

$ErrorActionPreference = 'Continue'
$Package = 'com.laya.decide'

$script:Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($ApkRoot -eq '') { $ApkRoot = Join-Path $script:Root 'app\app\build\outputs\apk' }

function Fail($m) { Write-Host ''; Write-Host ('  x ' + $m) -ForegroundColor Red; exit 1 }
function Info($m) { Write-Host $m }
function Ok($m)   { Write-Host ('  OK ' + $m) -ForegroundColor Green }

# 模型不在仓库里（262MB，不适合进 git）。按优先级在常见位置找，
# 都没有就提示先跑 fetch-model.ps1。
function Find-Model {
    $fileName = 'model_int4.onnx'
    $cands = @()
    if ($Model -ne '') { $cands += $Model }
    $cands += @(
        (Join-Path $script:Root "reference\models\$fileName"),
        (Join-Path $script:Root "models\$fileName"),
        (Join-Path $script:Root "reference\$fileName"),
        (Join-Path $script:Root $fileName),
        (Join-Path (Split-Path -Parent $script:Root) $fileName)
    )
    foreach ($c in $cands) {
        if ((Test-Path $c) -and ((Get-Item $c).Length -gt 250MB)) { return (Resolve-Path $c).Path }
    }
    return $null
}

if (-not (Test-Path $Adb)) { Fail ('找不到 adb: ' + $Adb) }

# --- 1. 找设备 -----------------------------------------------------------

Info ''
Info '  > 查找设备 ...'
$devices = @()
$raw = & $Adb devices 2>&1
foreach ($line in $raw) {
    if ($line -match '^(\S+)\s+device$') { $devices += $Matches[1] }
}

if ($devices.Count -eq 0) {
    Info '    没有检测到设备。检查：'
    Info '      1) 手机开了「USB 调试」吗'
    Info '      2) 数据线插好了吗（有些线只充电不传数据）'
    Info '      3) 手机上有没有弹「允许 USB 调试」'
    Info ''
    Info '    当前 adb 输出：'
    $raw | ForEach-Object { Info ('      ' + $_) }
    exit 1
}
if ($devices.Count -gt 1) {
    Info ('    检测到多个设备：' + ($devices -join ', '))
    Fail '请只连一台设备，或用 -Adb 指定 -s 参数'
}

$serial = $devices[0]
Info ('    设备: ' + $serial)

$model = (& $Adb -s $serial shell getprop ro.product.model 2>&1 | Out-String).Trim()
$android = (& $Adb -s $serial shell getprop ro.build.version.release 2>&1 | Out-String).Trim()
$sdk = (& $Adb -s $serial shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
Info ("    机型: $model   Android $android (API $sdk)")

if ([int]$sdk -lt 26) { Fail ('系统太旧（API ' + $sdk + '），本 App 需要 Android 8.0 (API 26) 以上') }

# --- 2. 选对 ABI 的包 ----------------------------------------------------

$abis = (& $Adb -s $serial shell getprop ro.product.cpu.abilist 2>&1 | Out-String).Trim()
Info ('    ABI : ' + $abis)

$variant = 'arm64-v8a'
if ($abis -notmatch 'arm64-v8a') {
    if ($abis -match 'armeabi-v7a') { $variant = 'armeabi-v7a' }
    else { Fail ('不支持的 CPU 架构: ' + $abis) }
}

$flavor = if ($DebugBuild) { 'debug' } else { 'release' }
$apk = Join-Path $ApkRoot "$flavor\app-$variant-$flavor.apk"
if (-not (Test-Path $apk)) {
    Fail ("找不到安装包: $apk`n  先编译： " + (Join-Path $script:Root 'build.ps1'))
}

Info ''
Info ("  > 安装 $variant $flavor 版 ...")
# 签名不同（比如之前装过 debug）时 -r 会失败，所以先卸再装。
$install = & $Adb -s $serial install -r $apk 2>&1 | Out-String
if ($install -notmatch 'Success') {
    Info '    直接覆盖失败，先卸载再装 ...'
    & $Adb -s $serial uninstall $Package 2>&1 | Out-Null
    $install = & $Adb -s $serial install $apk 2>&1 | Out-String
    if ($install -notmatch 'Success') { Fail ('安装失败: ' + $install) }
}
Ok '安装完成'

# --- 3. 可选：传模型 -----------------------------------------------------

if ($WithModel) {
    $modelPath = Find-Model
    if (-not $modelPath) {
        Info ''
        Info '  ! 加了 -WithModel，但找不到 model_int4.onnx。'
        Info '    先跑一次下载脚本：'
        Info ('        ' + (Join-Path $script:Root 'fetch-model.ps1'))
        Info '    或用 -Model 指定位置。'
        Info '    （App 仍已装好，可以稍后在 App 内点「下载模型」）'
        Info ''
        exit 1
    }
    $sizeMb = [math]::Round((Get-Item $modelPath).Length / 1MB, 1)
    $remote = '/data/local/tmp/laya-model.onnx'
    Info ''
    Info ("  > 传输模型 ($sizeMb MB) ...")
    Info ('    来源: ' + $modelPath)
    $push = & $Adb -s $serial push $modelPath $remote 2>&1 | Out-String
    if ($push -notmatch '1 file pushed') { Fail ('推送失败: ' + $push) }
    $remoteSize = ((& $Adb -s $serial shell "stat -c %s $remote 2>/dev/null" 2>&1 | Out-String) -replace '\D', '')
    $localSize = (Get-Item $Model).Length
    if ($remoteSize -ne "$localSize") { Fail ("大小不一致：本地 $localSize，设备 $remoteSize") }
    Ok '模型已就位，App 会自动加载'
} else {
    Info ''
    Info '  ! 还没传模型。装完打开 App 会是空的，两种补法：'
    Info '      1) 重跑本脚本加 -WithModel'
    Info '      2) App 内 设置 → 端侧模型 → 下载模型（走镜像，无需账号）'
}

# --- 4. 启动 -------------------------------------------------------------

Info ''
Info '  > 启动 App ...'
& $Adb -s $serial shell am start -n "$Package/.MainActivity" 2>&1 | Out-Null

if ($DebugBuild) {
    Info ''
    Info '  debug 版可以看日志（另开一个窗口跑）：'
    Info ('      & "' + $Adb + '" -s ' + $serial + ' logcat -s LayaModel:* AndroidRuntime:E')
}

Info ''
Ok '全部完成'
Info ''
