# 无 Gradle 的最小 Android 构建链：javac -> d8 -> aapt2 -> zipalign -> apksigner
# 用法： pwsh -NoProfile -File android/build.ps1 [-Install] [-Toolchain <dir>]
param(
    [switch]$Install,
    [string]$Toolchain = $env:S1REC_TOOLCHAIN
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repo = Split-Path $here -Parent
$build = Join-Path $here 'build'

if (-not $Toolchain) { $Toolchain = Join-Path $repo 'toolchain' }
if (-not (Test-Path $Toolchain)) {
    throw "找不到工具链 $Toolchain，先运行 tools/fetch_toolchain.ps1"
}

function Find-One($pattern, $root) {
    $f = Get-ChildItem $root -Recurse -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $f) { throw "找不到 $pattern（在 $root 下）" }
    return $f.FullName
}

# 工具链根目录下递归查找，兼容两种布局：
#   toolchain/{jdk,build-tools,platforms}          （tools/fetch_toolchain.ps1 产出）
#   <任意根>/{jdk,sdk/{build-tools,platforms}}      （临时/混合布局）
$javaExe = Find-One 'java.exe' $Toolchain
$javac   = Find-One 'javac.exe' $Toolchain
$keytool = Find-One 'keytool.exe' $Toolchain
$javaHome = Split-Path (Split-Path $javaExe -Parent) -Parent
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

$androidJar = Find-One 'android.jar' $Toolchain
$aapt2      = Find-One 'aapt2.exe' $Toolchain
$d8bat      = Find-One 'd8.bat' $Toolchain
$zipalign   = Find-One 'zipalign.exe' $Toolchain
$apksigner  = Find-One 'apksigner.jar' $Toolchain

Write-Output "JDK       : $javaHome"
Write-Output "android.jar: $androidJar"
Write-Output "aapt2     : $aapt2"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null

Write-Output '--- 1/5 javac ---'
$sources = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -nowarn -Xlint:-options -source 8 -target 8 `
    -classpath $androidJar -d "$build\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Output '--- 2/5 d8 ---'
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8bat --min-api 27 --release --lib $androidJar --output "$build\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Output '--- 3/5 aapt2 link ---'
$base = "$build\base.apk"
& $aapt2 link -o $base --manifest "$here\AndroidManifest.xml" -I $androidJar `
    --min-sdk-version 27 --target-sdk-version 27 `
    -A "$here\assets" `
    --version-code 2 --version-name 1.4
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
        -dname "CN=Android Debug,O=S1Rec,C=CN" 2>&1 | Out-Null
}
$apk = "$build\s1rec.apk"
& $javaExe -jar $apksigner sign --ks $ks --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Output ("APK       : {0}  ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))

if ($Install) {
    Write-Output '--- adb install ---'
    & adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "安装失败" }
    & adb shell am force-stop dev.s1rec.reader
    & adb shell am start -n dev.s1rec.reader/.MainActivity
}
