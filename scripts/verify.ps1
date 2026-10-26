# V1 一键验证：自检 → 单元测试 → e2e → 5 个验收脚本 → 恢复演练 → 性能 → Android 构建
# 用法： pwsh -NoProfile -File scripts/verify.ps1 [-SkipAndroid]
param(
    [switch]$SkipAndroid,
    [string]$Toolchain = 'C:\Program Files\Android\openjdk\jdk-21.0.8',
    [string]$Sdk = 'C:\Program Files (x86)\Android\android-sdk'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force -Path (Join-Path $root '.tmp') | Out-Null
$failed = @()

function Step($name, [scriptblock]$body) {
    Write-Host ''
    Write-Host "=== $name ===" -ForegroundColor Cyan
    try {
        & $body
        if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
            throw "exit $LASTEXITCODE"
        }
    } catch {
        Write-Host "[FAIL] $name : $_" -ForegroundColor Red
        $script:failed += $name
    }
}

Step '运行自检' { python (Join-Path $root 'scripts\selftest.py') }
Step '服务端单元测试' {
    Push-Location $root
    try { python -m unittest discover -s server\tests } finally { Pop-Location }
}
Step '端到端冒烟' { python (Join-Path $root 'scripts\e2e_smoke.py') }
Step '5 个验收脚本' { python (Join-Path $root 'scripts\acceptance.py') }
Step '备份恢复/回滚演练' { python (Join-Path $root 'scripts\restore_drill.py') }
Step '性能基准' { python (Join-Path $root 'scripts\bench.py') }

if (-not $SkipAndroid) {
    if ((Test-Path $Toolchain) -and (Test-Path $Sdk)) {
        Step 'Android 构建' {
            pwsh -NoProfile -File (Join-Path $root 'android\build.ps1') `
                -Toolchain $Toolchain -Sdk $Sdk
        }
    } else {
        Write-Host "[SKIP] 找不到 Android 工具链（$Toolchain / $Sdk）" -ForegroundColor Yellow
    }
}

Write-Host ''
if ($failed.Count -eq 0) {
    Write-Host '全部验证通过' -ForegroundColor Green
    exit 0
}
Write-Host ("失败项：" + ($failed -join '、')) -ForegroundColor Red
exit 1
