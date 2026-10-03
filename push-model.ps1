# 把 Laya 模型传到设备，免去在手机上重新下载 262MB。
#
#   .\push-model.ps1
#   .\push-model.ps1 -Serial emulator-5554
#
# 推到 /data/local/tmp/laya-model.onnx。
#
# 为什么是这个位置：Android 11+ 的 scoped storage 下，adb shell 既不能读写
# Android/data/<包名>/（所以没法直接推进 App 的外部私有目录），也没法读回来校验。
# /data/local/tmp 是唯一同时满足「shell 可写」且「应用可读」的位置，
# App 会把它作为候选路径之一。
#
# ⚠️ 本文件必须保存为 UTF-8 带 BOM。Windows PowerShell 5.1 读无 BOM 的 .ps1
#    会按 ANSI/GBK 解码，中文变乱码、引号错位，报出一堆假的语法错。
#
# 注意：adb 会把正常进度写到 stderr，PowerShell 的 $ErrorActionPreference='Stop'
# 会把它当致命错误。所以 adb 调用统一走 Adb 函数，只看 stdout。

[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$Adb = 'C:\Android\platform-tools\adb.exe',
    [string]$Model = 'D:\laya\reference\models\model_int4.onnx'
)

$ErrorActionPreference = 'Continue'

function Fail($m) { Write-Host ('  x ' + $m) -ForegroundColor Red; exit 1 }
function Info($m) { Write-Host $m }

if (-not (Test-Path $Adb)) { Fail ('找不到 adb: ' + $Adb) }
if (-not (Test-Path $Model)) { Fail ('找不到模型: ' + $Model) }

$script:devArgs = @()
if ($Serial -ne '') { $script:devArgs = @('-s', $Serial) }

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CmdArgs)
    $all = $script:devArgs + $CmdArgs
    return ((& $Adb @all 2>&1 | Out-String))
}

$remote = '/data/local/tmp/laya-model.onnx'
$localSize = (Get-Item $Model).Length
$localMd5 = (Get-FileHash $Model -Algorithm MD5).Hash.ToLower()
$sizeMb = [math]::Round($localSize / 1MB, 1)

# 设备上已经有一份一样的就跳过（每次 262MB 很浪费时间）
$existingMd5 = (Adb shell "md5sum $remote 2>/dev/null")
if ($existingMd5 -match $localMd5) {
    Info ''
    Info '  OK 设备上已有同一份模型（md5 一致），跳过传输'
    Info ('     ' + $remote)
    exit 0
}

Info ''
Info ("  > 传输模型 ($sizeMb MB) ...")
$pushOut = Adb push $Model $remote
if ($pushOut -notmatch '1 file pushed') { Fail ('adb push 失败: ' + $pushOut) }

Info '  > 校验 ...'
$remoteSize = ((Adb shell "stat -c %s $remote 2>/dev/null") -replace '\D', '')
$remoteMd5 = (Adb shell "md5sum $remote 2>/dev/null")

Info ("    本地: $localSize 字节  md5=$localMd5")
Info ("    设备: $remoteSize 字节  md5=$(($remoteMd5 -split '\s+')[0])")

if ($remoteSize -ne "$localSize") {
    Info ''
    Fail '大小不一致，传输可能损坏'
}

if ($remoteMd5 -match $localMd5) {
    Info ''
    Info '  OK 模型已就位（md5 一致）'
    Info '     App 会自动从 /data/local/tmp 加载，无需再操作。'
    exit 0
}

Info ''
Fail 'md5 不一致'
