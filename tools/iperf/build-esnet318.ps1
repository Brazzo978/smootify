$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$source = Join-Path $root 'upstream-3.18'
$srcDir = Join-Path $source 'src'
$aospConfig = Join-Path $root 'android-iperf_config.h'
$ndk = Join-Path $root '..\android-ndk-r28c'
$clang = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$output = Join-Path $root 'iperf3-arm64-esnet318'
$revision = '2a2984488d6de8f7a2d1f5938e03ca7be57e227c'
if (-not (Test-Path -LiteralPath $source)) {
    & git -c http.sslBackend=openssl clone --depth 1 --branch 3.18 https://github.com/esnet/iperf.git $source
    if ($LASTEXITCODE -ne 0) { throw 'ESnet clone failed' }
}
$actualRevision = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualRevision -ne $revision) { throw "Unexpected ESnet revision: $actualRevision" }
if (-not (Test-Path -LiteralPath $clang)) { throw "NDK compiler missing: $clang" }
if (-not (Test-Path -LiteralPath $aospConfig)) { throw "Android config missing: $aospConfig" }
$cfg = (Get-Content -LiteralPath $aospConfig -Raw).Replace('3.6+', '3.18')
$cfg += "`n#define HAVE_PTHREAD 1`n`n#define HAVE_STDATOMIC_H 1`n"
Set-Content -LiteralPath (Join-Path $srcDir 'iperf_config.h') -Value $cfg -Encoding Ascii
$version = (Get-Content -LiteralPath (Join-Path $srcDir 'version.h.in') -Raw).Replace('@PACKAGE_VERSION@', '3.18')
Set-Content -LiteralPath (Join-Path $srcDir 'version.h') -Value $version -Encoding Ascii
$sources = @(Get-ChildItem -LiteralPath $srcDir -Filter '*.c' | Where-Object { $_.Name -notlike 't_*.c' } | ForEach-Object { $_.FullName })
& $clang --target=aarch64-linux-android26 -O2 -fPIE -pie -Wno-unused-parameter -Wno-unused-variable -Wno-format -I $srcDir @sources -o $output -pthread
if ($LASTEXITCODE -ne 0) { throw "Clang failed with exit code $LASTEXITCODE" }
Get-FileHash -Algorithm SHA256 -LiteralPath $output

$destination = Join-Path $root '..\..\upstream\android\app\src\main\jniLibs\arm64-v8a\libiperf_exec.so'
Copy-Item -LiteralPath $output -Destination $destination -Force
