# 下载运行所需的模型与分词器文件。
#
# 仓库里**不含**这些文件（模型 262MB，不适合进 git），所以 clone 之后要先跑一次这个。
#
#   .\fetch-model.ps1                      下载模型 + 分词器（约 262MB）
#   .\fetch-model.ps1 -SkipModel           只下载分词器（约 3.5MB，够跑验证工具）
#   .\fetch-model.ps1 -OutDir D:\models    换个存放位置
#
# 模型是 Laya（Convai Innovations，Apache-2.0）的 int4 量化 ONNX 导出。
# 走 hf-mirror.com 镜像，不需要任何账号。
#
# ⚠️ 本文件必须保存为 UTF-8 带 BOM（Windows PowerShell 5.1 的要求）。

[CmdletBinding()]
param(
    [string]$OutDir = '',
    [switch]$SkipModel,
    [switch]$Force
)

$ErrorActionPreference = 'Continue'

function Fail($m) { Write-Host ''; Write-Host ('  x ' + $m) -ForegroundColor Red; exit 1 }
function Info($m) { Write-Host $m }
function Ok($m)   { Write-Host ('  OK ' + $m) -ForegroundColor Green }

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($OutDir -eq '') { $OutDir = Join-Path $root 'reference' }

# 两个下载源：先镜像（国内可达），失败再试官方站
$MIRROR = 'https://hf-mirror.com/techtheist/laya-onnx/resolve/main/en'
$OFFICIAL = 'https://huggingface.co/techtheist/laya-onnx/resolve/main/en'

function Fetch($name, $dest, $minBytes) {
    if ((Test-Path $dest) -and -not $Force) {
        $sz = (Get-Item $dest).Length
        if ($sz -ge $minBytes) {
            Ok ("已存在，跳过: $name  (" + [math]::Round($sz / 1MB, 2) + " MB)")
            return $true
        }
        Info ("    已存在但偏小（" + $sz + " 字节），重新下载")
    }

    $dir = Split-Path -Parent $dest
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

    foreach ($base in @($MIRROR, $OFFICIAL)) {
        $url = "$base/$name"
        Info ("    " + $url)
        & curl.exe -sL --fail --max-time 3600 -o $dest $url 2>&1 | Out-Null
        if ((Test-Path $dest) -and ((Get-Item $dest).Length -ge $minBytes)) {
            Ok ("下载完成: $name  (" + [math]::Round((Get-Item $dest).Length / 1MB, 2) + " MB)")
            return $true
        }
        Info '      这个源不行，换下一个'
    }
    return $false
}

$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue)
if (-not $curl) { Fail '找不到 curl.exe。Windows 10 1803+ 自带，请确认系统版本。' }

Info ''
Info ("  > 下载目录: " + $OutDir)
Info ''

$ok = $true

# 1) 分词器与配置（小，验证工具必需）
Info '  > 分词器与模型配置（约 3.5 MB）'
$ok = (Fetch 'tokenizer.json'          (Join-Path $OutDir 'en\tokenizer.json')          3000000) -and $ok
$ok = (Fetch 'tokenizer_config.json'   (Join-Path $OutDir 'en\tokenizer_config.json')   200)     -and $ok
$ok = (Fetch 'rl_agent_config.json'    (Join-Path $OutDir 'en\rl_agent_config.json')    500)     -and $ok

# 2) 模型权重
if (-not $SkipModel) {
    Info ''
    Info '  > 模型权重 int4（约 262 MB，用于端侧推理）'
    $ok = (Fetch 'model_int4.onnx' (Join-Path $OutDir 'models\model_int4.onnx') 250000000) -and $ok
} else {
    Info ''
    Info '  > 跳过了模型权重（-SkipModel）'
    Info '    注意：没有它 App 无法做端侧推理，安装时也不能带 -WithModel。'
}

Info ''
if ($ok) {
    Ok '全部就绪'
    Info ''
    Info '  接下来可以：'
    Info ('    编译      D:\laya\build.ps1')
    Info ('    装到手机  D:\laya\install-to-phone.ps1 -WithModel')
    Info ('    传模型    D:\laya\push-model.ps1')
} else {
    Info '  部分文件下载失败。手动下载地址：'
    Info ('    ' + $MIRROR + '/')
    Info ('    下载后放到: ' + $OutDir)
    exit 1
}
Info ''
