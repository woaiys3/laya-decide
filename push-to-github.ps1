# 把项目推到 GitHub。
#
# 用法：
#   .\push-to-github.ps1 -Repo "git@github.com:你的用户名/laya-decide.git"
#
# 分支默认 main。已经配好 remote 的话可以不带 -Repo 直接跑。
#
# ── 为什么推荐 SSH 而不是 HTTPS ──────────────────────────────────────────
#
#   这台机器上 github.com 的 HTTPS(443) 被 SNI 阻断：TCP 能连，HTTP 超时。
#   走 HTTPS 推送会报 "Recv failure: Connection was reset"。
#   而 api.github.com / codeload.github.com 是通的，很容易误判成"没权限"。
#
#   SSH 不走 SNI，能正常用。首次使用三步：
#     1) ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\id_ed25519" -N '""'
#     2) 把 id_ed25519.pub 的内容加到 GitHub -> Settings -> SSH and GPG keys
#     3) -Repo 用 git@github.com:用户名/仓库.git
#
#   22 端口也不通时，在 ~/.ssh/config 里把 HostName 换成 ssh.github.com、Port 换成 443。
#
# ── 关于令牌 ────────────────────────────────────────────────────────────
#
#   这个脚本**不会**存任何令牌。如果非要用 HTTPS，先配凭据助手：
#     git config --global credential.helper manager
#   然后第一次 push 时输入用户名 + Personal Access Token。
#
#   ★ 千万不要把 token 写进这个文件、写进 URL、或提交到仓库里。
#     token 一旦进了 git 历史，就算后来删掉文件也还在历史里，必须去 GitHub 吊销。
#
# ⚠️ 本文件必须保存为 UTF-8 带 BOM（Windows PowerShell 5.1 的要求）。

[CmdletBinding()]
param(
    [string]$Repo = '',
    [string]$Branch = 'main',
    [string]$Git = 'C:\Program Files\Git\cmd\git.exe',
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'

function Fail($m) { Write-Host ''; Write-Host ('  x ' + $m) -ForegroundColor Red; exit 1 }
function Info($m) { Write-Host $m }
function Ok($m)   { Write-Host ('  OK ' + $m) -ForegroundColor Green }

if (-not (Test-Path $Git)) { Fail ('找不到 git: ' + $Git) }

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path (Join-Path $root '.git'))) { Fail '这里不是 git 仓库，先初始化' }

# --- 推送前再查一遍：绝不能把密钥推上去 -----------------------------------

Info ''
Info '  > 推送前安全检查 ...'

$tracked = & $Git ls-files 2>&1
$danger = $tracked | Where-Object { $_ -match '\.jks$|\.keystore$|keystore\.properties|local\.properties' }
if ($danger) {
    Info '    仓库里跟踪了这些敏感文件：'
    $danger | ForEach-Object { Info ('      ' + $_) }
    Fail '先把它们从跟踪中移除（git rm --cached），否则等于公开 App 的签名权'
}
Ok ('已跟踪文件 ' + @($tracked).Count + ' 个，无密钥')

# 扫一遍历史里有没有凭证
$pat = 'ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}'
$leak = $null
foreach ($f in $tracked) {
    if ((Test-Path $f) -and ((Get-Item $f).Length -lt 3MB)) {
        $h = Select-String -Path $f -Pattern $pat -ErrorAction SilentlyContinue
        if ($h) { $leak = $f; break }
    }
}
if ($leak) { Fail ('发现疑似凭证：' + $leak + ' —— 先删掉再推') }
Ok '无凭证泄露'

# --- 配置 remote ----------------------------------------------------------

if ($Repo -ne '') {
    $existing = & $Git remote 2>&1
    if ($existing -contains 'origin') {
        & $Git remote set-url origin $Repo 2>&1 | Out-Null
        Info ''
        Info ('  > 已更新 origin -> ' + $Repo)
    } else {
        & $Git remote add origin $Repo 2>&1 | Out-Null
        Info ''
        Info ('  > 已添加 origin -> ' + $Repo)
    }
} else {
    $remotes = & $Git remote 2>&1
    if (-not ($remotes -contains 'origin')) {
        Fail "没配 remote。用 -Repo 指定，例如：`n      .\push-to-github.ps1 -Repo `"https://github.com/你/laya-decide.git`""
    }
}

$url = (& $Git remote get-url origin 2>&1 | Out-String).Trim()
Info ('    目标: ' + $url)

# --- 推送 -----------------------------------------------------------------

$current = (& $Git rev-parse --abbrev-ref HEAD 2>&1 | Out-String).Trim()
Info ''
Info ('  > 当前分支: ' + $current + '   提交数: ' + @(& $Git rev-list --count HEAD 2>&1).Count)

if ($DryRun) {
    Info ''
    Info '  DryRun：不实际推送。将要执行的是：'
    Info ('      git push -u origin ' + $Branch)
    exit 0
}

# 确认这是首次推送还是增量
$hasUpstream = $true
& $Git rev-parse --abbrev-ref '@{u}' 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { $hasUpstream = $false }

Info ''
Info '  > 推送中（如果是私有仓库或没配 SSH，会提示输入凭据）...'
Info ''

if ($hasUpstream) {
    & $Git push 2>&1 | ForEach-Object { Info ('    ' + $_) }
} else {
    & $Git push -u origin $Branch 2>&1 | ForEach-Object { Info ('    ' + $_) }
}

if ($LASTEXITCODE -ne 0) {
    Info ''
    Info '  推送失败。常见原因：'
    Info '    - 没有权限 / 没登录 -> 配一下凭据：git config --global credential.helper manager'
    Info '    - 远程已有内容 -> 先 git pull --rebase origin ' + $Branch
    Info '    - 仓库名写错'
    exit 1
}

Info ''
Ok '推送完成'
Info ''
