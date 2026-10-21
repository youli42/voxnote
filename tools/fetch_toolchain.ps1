# 获取最小 Android 工具链（JDK + build-tools + platform），不安装 Android Studio
# 用法： pwsh -NoProfile -File tools/fetch_toolchain.ps1 [-Proxy http://127.0.0.1:10808] [-Dest <dir>]
param(
    [string]$Proxy,
    [string]$Dest
)

$ErrorActionPreference = 'Stop'
if (-not $Proxy) { $Proxy = $env:VOXNOTE_PROXY }
if (-not $Proxy) { $Proxy = $env:S1REC_PROXY }
$repo = Split-Path $PSScriptRoot -Parent
if (-not $Dest) { $Dest = Join-Path $repo 'toolchain' }
$Dest = [System.IO.Path]::GetFullPath($Dest)
$dl = Join-Path $Dest 'dl'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

if ($Proxy) {
    $env:HTTP_PROXY = $Proxy
    $env:HTTPS_PROXY = $Proxy
    Write-Output "使用代理: $Proxy"
}
$proxyArgs = @()
if ($Proxy) { $proxyArgs = @('--proxy', $Proxy) }

function Get-File($url, $out) {
    if ((Test-Path $out) -and ((Get-Item $out).Length -gt 1MB)) {
        Write-Output "已存在 $(Split-Path $out -Leaf)"
        return
    }
    Write-Output "下载 $(Split-Path $out -Leaf) ..."
    & curl.exe -sL @proxyArgs --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 1800 -o $out $url
    if ($LASTEXITCODE -ne 0) { throw "下载失败($LASTEXITCODE): $url" }
    Write-Output ("  {0:N1} MB" -f ((Get-Item $out).Length / 1MB))
}

Get-File "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip" (Join-Path $dl 'jdk.zip')
Get-File "https://dl.google.com/android/repository/build-tools_r34-windows.zip" (Join-Path $dl 'bt34.zip')
# 编译用 platform 36：代码里用到 API 29+ 的符号（MediaStore 分区存储），
# 只抓 platform-27 会编译不过；targetSdk 仍由 build.ps1 控制（34）。
Get-File "https://dl.google.com/android/repository/platform-36_r02.zip" (Join-Path $dl 'plat36.zip')

$targets = @(
    @((Join-Path $dl 'jdk.zip'),    (Join-Path $Dest 'jdk')),
    @((Join-Path $dl 'bt34.zip'),   (Join-Path $Dest 'build-tools')),
    @((Join-Path $dl 'plat36.zip'), (Join-Path $Dest 'platforms'))
)
foreach ($t in $targets) {
    if (Test-Path $t[1]) { Write-Output "已解压 $(Split-Path $t[1] -Leaf)"; continue }
    Write-Output "解压 $(Split-Path $t[1] -Leaf) ..."
    New-Item -ItemType Directory -Force -Path $t[1] | Out-Null
    Expand-Archive -Path $t[0] -DestinationPath $t[1] -Force
}

$java   = Get-ChildItem (Join-Path $Dest 'jdk') -Recurse -Filter javac.exe | Select-Object -First 1
$aapt2  = Get-ChildItem (Join-Path $Dest 'build-tools') -Recurse -Filter aapt2.exe | Select-Object -First 1
$ajar   = Get-ChildItem (Join-Path $Dest 'platforms') -Recurse -Filter android.jar | Select-Object -First 1
if (-not ($java -and $aapt2 -and $ajar)) { throw "工具链不完整，请检查上面的下载/解压输出" }

Write-Output ''
Write-Output "工具链就绪: $Dest"
Write-Output "  javac     : $($java.FullName)"
Write-Output "  aapt2     : $($aapt2.FullName)"
Write-Output "  android.jar: $($ajar.FullName)"
