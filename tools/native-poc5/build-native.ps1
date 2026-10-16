$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$native = $PSScriptRoot
$logDir = Join-Path $repo 'tools\native-poc5\logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
Start-Transcript -Path (Join-Path $logDir 'native-build.log') -Append | Out-Null
$deps = Join-Path $native 'deps'
$ndk = Join-Path $repo 'tools\android-ndk-r28c'
$cmake = Join-Path $native 'pytools\cmake\data\bin\cmake.exe'
$ninjaDir = Join-Path $native 'pytools\bin'
$llvmDir = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$toolchain = Join-Path $ndk 'build\cmake\android.toolchain.cmake'
$upstream = Join-Path $repo 'upstream'
$abi = 'arm64-v8a'
$api = '26'
$xq = Join-Path $deps 'xquic-5f63f07e31fd3fac027d77713789c068f2e794d5'
$lw = Join-Path $deps 'heiher-lwip-821af1994c4dbaf1e2cba9a7c2207303b3e5327b'
$bs = Join-Path $deps 'boringssl-0ce57bbf06a35864fa39e3b9346d41e833683383'
$b = Join-Path $native 'b'
$boringBuild = Join-Path $b 'boringssl'
$xquicBuild = Join-Path $b 'xquic'
$mqvpnBuild = Join-Path $b 'mqvpn'
$jniBuild = Join-Path $b 'jni'
$prebuilt = Join-Path $upstream 'android\sdk-native\prebuilt\arm64-v8a'

foreach ($p in @($cmake, (Join-Path $ninjaDir 'ninja.exe'), $toolchain, $xq, $lw, $bs)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing prerequisite: $p" }
}
$archiveManifest = Get-Content -LiteralPath (Join-Path $native 'archives.sha256.json') -Raw | ConvertFrom-Json
foreach ($file in $archiveManifest.PSObject.Properties) {
    $path = Join-Path $native $file.Name
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing pinned archive: $path" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $file.Value.sha256) { throw "Pinned archive checksum mismatch: $path" }
}
function Ensure-Junction([string]$link, [string]$target) {
    if (Test-Path -LiteralPath $link) {
        $item = Get-Item -LiteralPath $link -Force
        if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            $expectedPlaceholder = Join-Path $xq 'third_party\boringssl'
            $insidePinnedDeps = ([IO.Path]::GetFullPath($link) -eq [IO.Path]::GetFullPath($expectedPlaceholder))
            $isEmptyDirectory = $item.PSIsContainer -and -not @(Get-ChildItem -LiteralPath $link -Force).Count
            if (-not ($insidePinnedDeps -and $isEmptyDirectory)) { throw "Refusing existing populated or unexpected non-junction: $link" }
            Remove-Item -LiteralPath $link
            New-Item -ItemType Junction -Path $link -Target $target | Out-Null
            return
        }
        if (([IO.Path]::GetFullPath($item.Target)) -ne ([IO.Path]::GetFullPath($target))) { throw "Junction target mismatch: $link" }
        return
    }
    New-Item -ItemType Junction -Path $link -Target $target | Out-Null
}
Ensure-Junction (Join-Path $upstream 'third_party\xquic') $xq
Ensure-Junction (Join-Path $upstream 'third_party\lwip') $lw
Ensure-Junction (Join-Path $xq 'third_party\boringssl') $bs
$env:PATH = "$ninjaDir;$llvmDir;$env:PATH"
$common = @('-G', 'Ninja', "-DCMAKE_TOOLCHAIN_FILE=$toolchain", "-DANDROID_ABI=$abi", "-DANDROID_NATIVE_API_LEVEL=$api", '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POLICY_VERSION_MINIMUM=3.5')
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe failed with exit code $LASTEXITCODE" }
}
Run $cmake (@('-S', $bs, '-B', $boringBuild) + $common + @('-DBUILD_SHARED_LIBS=OFF'))
Run $cmake @('--build', $boringBuild, '--target', 'ssl', 'crypto', '--parallel', '8')
$ssl = Join-Path $boringBuild 'ssl\libssl.a'
$crypto = Join-Path $boringBuild 'crypto\libcrypto.a'
if (-not (Test-Path $ssl)) { $ssl = Join-Path $boringBuild 'libssl.a' }
if (-not (Test-Path $crypto)) { $crypto = Join-Path $boringBuild 'libcrypto.a' }
foreach ($p in @($ssl, $crypto)) { if (-not (Test-Path $p)) { throw "Missing BoringSSL archive: $p" } }
Run $cmake (@('-S', $xq, '-B', $xquicBuild) + $common + @(
    '-DCMAKE_BUILD_TYPE=MinSizeRel',
    '-DCMAKE_C_FLAGS=-Wno-unknown-warning-option',
    '-DSSL_TYPE=boringssl', "-DSSL_PATH=$boringBuild", "-DSSL_INC_PATH=$(Join-Path $bs 'include')",
    '-DXQC_ENABLE_BBR2=ON', '-DXQC_ENABLE_FEC=ON', '-DXQC_ENABLE_XOR=ON',
    '-DXQC_ENABLE_TH3=ON', '-DXQC_ENABLE_TESTING=OFF', '-DXQC_BUILD_SAMPLE=OFF',
    '-DXQC_ONLY_ERROR_LOG=ON', '-DXQC_COMPAT_GENERATE_SR_PKT=ON',
    '-DXQC_NO_PID_PACKET_PROCESS=1', '-DBUILD_SHARED_LIBS=OFF'
))
Run $cmake @('--build', $xquicBuild, '--target', 'xquic-static', '--parallel', '8')
Run $cmake (@('-S', $upstream, '-B', $mqvpnBuild) + $common + @(
    "-DXQUIC_BUILD_DIR=$xquicBuild", "-DBORINGSSL_BUILD_DIR=$boringBuild", '-DANDROID_CROSS_COMPILE=ON'
))
Run $cmake @('--build', $mqvpnBuild, '--target', 'mqvpn_lib', 'lwip_core', '--parallel', '8')
New-Item -ItemType Directory -Path $prebuilt -Force | Out-Null
$archives = @{
    'libmqvpn.a' = (Join-Path $mqvpnBuild 'libmqvpn.a')
    'liblwip_core.a' = (Join-Path $mqvpnBuild 'liblwip_core.a')
    'libxquic.a' = (Join-Path $xquicBuild 'libxquic-static.a')
    'libssl.a' = $ssl
    'libcrypto.a' = $crypto
}
foreach ($entry in $archives.GetEnumerator()) {
    if (-not (Test-Path $entry.Value)) { throw "Missing static archive: $($entry.Value)" }
    Copy-Item -LiteralPath $entry.Value -Destination (Join-Path $prebuilt $entry.Key) -Force
}
$jniSource = Join-Path $upstream 'android\sdk-native\src\main\jni'
Run $cmake (@('-S', $jniSource, '-B', $jniBuild) + $common + @('-DMQVPN_JNI_TEST_SEAMS=OFF'))
Run $cmake @('--build', $jniBuild, '--target', 'mqvpn_jni', '--parallel', '8')
$jni = Join-Path $jniBuild 'libmqvpn_jni.so'
if (-not (Test-Path $jni)) { throw "JNI output absent: $jni" }
$out = Join-Path $native 'out'
New-Item -ItemType Directory -Path $out -Force | Out-Null
Copy-Item -LiteralPath $jni -Destination (Join-Path $out 'libmqvpn_jni.so') -Force
Run (Join-Path $llvmDir 'llvm-strip.exe') @('--strip-unneeded', (Join-Path $out 'libmqvpn_jni.so'))
Get-FileHash -LiteralPath (Join-Path $out 'libmqvpn_jni.so') -Algorithm SHA256
Stop-Transcript | Out-Null
