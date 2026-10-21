# 无 Gradle 的最小 Android 构建链：javac -> d8 -> aapt2 -> zipalign -> apksigner
# 用法： pwsh -NoProfile -File android/build.ps1 [-Install] [-Toolchain <dir>] [-Sdk <dir>]
#
# -Toolchain 放 JDK（含 javac/keytool/java）
# -Sdk       放 build-tools 与 platforms（默认和 -Toolchain 同一个根）
#
# 编译用能找到的最高 API 的 android.jar（当前 API 36），targetSdk 见下方 34。
param(
    [switch]$Install,
    [string]$Toolchain,
    [string]$Sdk
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = Split-Path $here -Parent
$build = Join-Path $here 'build'
$TargetSdk = 34
$MinSdk = 27

if (-not $Toolchain) { $Toolchain = $env:VOXNOTE_TOOLCHAIN }
if (-not $Toolchain) { $Toolchain = $env:S1REC_TOOLCHAIN }
if (-not $Toolchain) { $Toolchain = Join-Path $repo 'toolchain' }
if (-not (Test-Path $Toolchain)) {
    throw "找不到工具链 $Toolchain，先运行 tools/fetch_toolchain.ps1"
}
if (-not $Sdk) { $Sdk = $Toolchain }
if (-not (Test-Path $Sdk)) { throw "找不到 SDK $Sdk" }

function Find-One($pattern, $root) {
    $f = Get-ChildItem $root -Recurse -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { throw "找不到 $pattern（在 $root 下）" }
    return $f.FullName
}

# 有多个 platform 时选 API 最高的那个 android.jar
function Find-HighestPlatformJar($root) {
    $jars = Get-ChildItem $root -Recurse -Filter android.jar -ErrorAction SilentlyContinue
    if (-not $jars) { throw "找不到 android.jar（在 $root 下）" }
    $best = $null; $bestApi = -1
    foreach ($j in $jars) {
        $m = [regex]::Match($j.FullName, 'android-(\d+)')
        $api = if ($m.Success) { [int]$m.Groups[1].Value } else { 0 }
        if ($api -gt $bestApi) { $bestApi = $api; $best = $j }
    }
    return $best.FullName
}

$javaExe = Find-One 'java.exe' $Toolchain
$javac   = Find-One 'javac.exe' $Toolchain
$keytool = Find-One 'keytool.exe' $Toolchain
$javaHome = Split-Path (Split-Path $javaExe -Parent) -Parent
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

$androidJar = Find-HighestPlatformJar $Sdk
$aapt2      = Find-One 'aapt2.exe' $Sdk
$d8bat      = Find-One 'd8.bat' $Sdk
$zipalign   = Find-One 'zipalign.exe' $Sdk
$apksigner  = Find-One 'apksigner.jar' $Sdk

Write-Output "JDK       : $javaHome"
Write-Output "android.jar: $androidJar"
Write-Output "aapt2     : $aapt2"
Write-Output "targetSdk : $TargetSdk (min $MinSdk)"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null

Write-Output '--- 1/5 javac ---'
$sources = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 `
    -classpath $androidJar -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Output '--- 2/5 d8 ---'
# 类文件多了以后，把几百个 .class 路径直接拼进命令行会超过 Windows 限制；
# 先打成 classes.jar 再交给 d8。
$jar = Find-One 'jar.exe' $Toolchain
$classesJar = "$build\classes.jar"
& $jar cf $classesJar -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw "打包 classes.jar 失败" }
& $d8bat --min-api $MinSdk --release --lib $androidJar --output "$build\dex" $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Output '--- 3/5 aapt2 link ---'
$base = "$build\base.apk"
& $aapt2 link -o $base --manifest "$here\AndroidManifest.xml" -I $androidJar `
    --min-sdk-version $MinSdk --target-sdk-version $TargetSdk `
    -A "$here\assets" `
    --version-code 3 --version-name 2.0
if ($LASTEXITCODE -ne 0) { throw "aapt2 失败" }

Write-Output '--- 4/5 打包 dex ---'
$unsigned = "$build\unsigned.apk"
& python "$here\package.py" $base "$build\dex\classes.dex" $unsigned
if ($LASTEXITCODE -ne 0) { throw "打包失败" }

$aligned = "$build\aligned.apk"
& $zipalign -f -p 4 $unsigned $aligned

Write-Output '--- 5/5 签名 ---'
$ks = Join-Path $here 'debug.keystore'
if (-not (Test-Path $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=voxnote,C=CN" 2>&1 | Out-Null
}
$apk = "$build\voxnote.apk"
& $javaExe -jar $apksigner sign --ks $ks --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Output ("APK       : {0}  ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))

if ($Install) {
    Write-Output '--- adb install ---'
    & adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "安装失败" }
    & adb shell am force-stop dev.voxnote
    & adb shell am start -n dev.voxnote/.HomeActivity
}
