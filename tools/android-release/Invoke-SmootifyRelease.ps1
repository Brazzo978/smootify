# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 mp0rta and mqvpn contributors
param(
  [switch] $InitializeKey,
  [string] $JdkPath = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot',
  [string] $SdkPath = '',
  [string] $PrivateDirectory = '',
  [string] $OutputDirectory = '',
  [string] $RegistrationProperties = ''
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$android = Join-Path $repo 'upstream\android'
$package = 'com.codmanu.smootify'
$alias = 'smootify-release'
if (-not $SdkPath) { $SdkPath = Join-Path $repo '..\tools\android-sdk' }
if (-not $PrivateDirectory) { $PrivateDirectory = Join-Path $repo '..\..\smootify-signing' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $PrivateDirectory 'artifacts' }
$SdkPath = [IO.Path]::GetFullPath($SdkPath)
$PrivateDirectory = [IO.Path]::GetFullPath($PrivateDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
foreach ($candidate in @($PrivateDirectory, $OutputDirectory)) {
  if ($candidate.Equals($repo, [StringComparison]::OrdinalIgnoreCase) -or
      $candidate.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing material and release output must stay outside the repository.'
  }
}
$keystore = Join-Path $PrivateDirectory 'smootify-release.p12'
$protectedPassword = Join-Path $PrivateDirectory 'smootify-release-password.dpapi'
$keytool = Join-Path $JdkPath 'bin\keytool.exe'
$jarsigner = Join-Path $JdkPath 'bin\jarsigner.exe'
$gradle = Join-Path $android 'gradlew.bat'
function Invoke-Checked([string] $Program, [string[]] $Arguments) {
  & $Program @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $([IO.Path]::GetFileName($Program))" }
}
function Get-Sha256([string] $Path) {
  $stream = [IO.File]::OpenRead($Path)
  try {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '') }
    finally { $sha.Dispose() }
  } finally { $stream.Dispose() }
}
function Get-CertFingerprint([byte[]] $Data) {
  $cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList (,$Data)
  try {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($cert.RawData))).Replace('-', '') }
    finally { $sha.Dispose() }
  } finally { $cert.Dispose() }
}
function Get-PemFingerprint([string] $Text) {
  $match = [regex]::Match($Text, '-----BEGIN CERTIFICATE-----\s*(?<data>[A-Za-z0-9+/=\s]+?)\s*-----END CERTIFICATE-----')
  if (-not $match.Success) { throw 'AAB certificate not found.' }
  return Get-CertFingerprint ([Convert]::FromBase64String(($match.Groups['data'].Value -replace '\s', '')))
}
foreach ($required in @($keytool, $jarsigner, $gradle)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required tool missing: $required" }
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkPath 'build-tools') -Directory |
  Where-Object { (Test-Path (Join-Path $_.FullName 'apksigner.bat')) -and (Test-Path (Join-Path $_.FullName 'aapt.exe')) } |
  Sort-Object { [version](($_.Name -split '-')[0]) } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android SDK build-tools with apksigner and aapt are required.' }
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
if ((Test-Path $keystore) -xor (Test-Path $protectedPassword)) { throw 'Incomplete signing identity.' }
if ($InitializeKey -and (Test-Path $keystore)) { throw 'Existing signing identity will not be replaced.' }
if (-not $InitializeKey -and -not (Test-Path $keystore)) { throw 'Signing identity missing. Run once with -InitializeKey.' }
$proofTarget = Join-Path $android 'app\src\release\assets\adi-registration.properties'
$proofDirectory = Split-Path -Parent $proofTarget
$proofCopied = $false
$createdProofDirectory = $false
$passwordBytes = $null
$plainBytes = $null
try {
  if ($InitializeKey) {
    New-Item -ItemType Directory -Path $PrivateDirectory -Force | Out-Null
    $random = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($random) } finally { $rng.Dispose() }
    $password = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    [Array]::Clear($random, 0, $random.Length)
    $plainBytes = [Text.Encoding]::UTF8.GetBytes($password)
    $passwordBytes = [Security.Cryptography.ProtectedData]::Protect($plainBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllBytes($protectedPassword, $passwordBytes)
    $env:SMOOTIFY_SIGNING_SECRET = $password
    try {
      Invoke-Checked $keytool @('-genkeypair', '-noprompt', '-keystore', $keystore,
        '-storetype', 'PKCS12', '-alias', $alias, '-keyalg', 'RSA', '-keysize', '3072',
        '-sigalg', 'SHA256withRSA', '-validity', '10000', '-dname', 'CN=Smootify',
        '-storepass:env', 'SMOOTIFY_SIGNING_SECRET', '-keypass:env', 'SMOOTIFY_SIGNING_SECRET')
    } catch {
      Remove-Item -LiteralPath $keystore, $protectedPassword -Force -ErrorAction SilentlyContinue
      throw
    }
  } else {
    $passwordBytes = [IO.File]::ReadAllBytes($protectedPassword)
    $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect($passwordBytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    $env:SMOOTIFY_SIGNING_SECRET = [Text.Encoding]::UTF8.GetString($plainBytes)
  }
  New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
  $certDer = Join-Path $OutputDirectory 'smootify-signing-certificate.der'
  $certPem = Join-Path $OutputDirectory 'smootify-signing-certificate.pem'
  Invoke-Checked $keytool @('-exportcert', '-keystore', $keystore, '-storetype', 'PKCS12',
    '-alias', $alias, '-file', $certDer, '-storepass:env', 'SMOOTIFY_SIGNING_SECRET')
  Invoke-Checked $keytool @('-exportcert', '-rfc', '-keystore', $keystore, '-storetype', 'PKCS12',
    '-alias', $alias, '-file', $certPem, '-storepass:env', 'SMOOTIFY_SIGNING_SECRET')
  $fingerprint = Get-CertFingerprint ([IO.File]::ReadAllBytes($certDer))
  if ($RegistrationProperties) {
    $RegistrationProperties = [IO.Path]::GetFullPath($RegistrationProperties)
    if ($RegistrationProperties.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Registration proof must stay outside the repository.' }
    if (-not (Test-Path -LiteralPath $RegistrationProperties -PathType Leaf)) { throw 'RegistrationProperties file missing.' }
    if (Test-Path -LiteralPath $proofTarget) { throw 'Proof target already exists; refusing to overwrite.' }
    if (-not (Test-Path -LiteralPath $proofDirectory)) {
      New-Item -ItemType Directory -Path $proofDirectory -Force | Out-Null
      $createdProofDirectory = $true
    }
    Copy-Item -LiteralPath $RegistrationProperties -Destination $proofTarget
    $proofCopied = $true
  }
  $env:SMOOTIFY_KEYSTORE_PATH = $keystore
  $env:SMOOTIFY_KEYSTORE_PASSWORD = $env:SMOOTIFY_SIGNING_SECRET
  $env:SMOOTIFY_KEY_ALIAS = $alias
  $env:SMOOTIFY_KEY_PASSWORD = $env:SMOOTIFY_SIGNING_SECRET
  $env:JAVA_HOME = $JdkPath
  $env:ANDROID_HOME = $SdkPath
  Push-Location $android
  try { Invoke-Checked $gradle @(':app:assembleRelease', ':app:bundleRelease', '--no-daemon', '--no-configuration-cache') }
  finally { Pop-Location }
  $apk = Join-Path $android 'app\build\outputs\apk\release\app-release.apk'
  $aab = Join-Path $android 'app\build\outputs\bundle\release\app-release.aab'
  if (-not (Test-Path $apk) -or -not (Test-Path $aab)) { throw 'Release APK or AAB missing.' }
  $apkCheck = (& $apksigner verify --verbose --print-certs $apk) -join [Environment]::NewLine
  if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
  $apkCert = [regex]::Match($apkCheck, '(?im)^Signer #1 certificate SHA-256 digest:\s*(?<hash>[A-Fa-f0-9]{64})\s*$')
  if (-not $apkCert.Success -or $apkCert.Groups['hash'].Value.ToUpperInvariant() -ne $fingerprint) {
    throw 'APK signer differs from Smootify certificate.'
  }
  $badging = (& $aapt dump badging $apk) -join [Environment]::NewLine
  if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
  $identity = [regex]::Match($badging, "(?m)^package: name='(?<id>[^']+)' versionCode='(?<code>[^']+)' versionName='(?<name>[^']+)'")
  if (-not $identity.Success -or $identity.Groups['id'].Value -ne $package) { throw "APK package must be $package." }
  if ($badging -match '(?m)^application-debuggable\b') { throw 'Release APK is debuggable.' }
  & $jarsigner -verify $aab | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'AAB signature verification failed.' }
  $bundleCert = (& $keytool -printcert -rfc -jarfile $aab) -join [Environment]::NewLine
  if ($LASTEXITCODE -ne 0 -or (Get-PemFingerprint $bundleCert) -ne $fingerprint) {
    throw 'AAB signer differs from Smootify certificate.'
  }
  $releaseName = 'smootify-' + $identity.Groups['name'].Value
  if ($RegistrationProperties) { $releaseName += '-registration' }
  $finalApk = Join-Path $OutputDirectory ($releaseName + '.apk')
  $finalAab = Join-Path $OutputDirectory ($releaseName + '.aab')
  if ((Test-Path $finalApk) -or (Test-Path $finalAab)) { throw 'Release artifact already exists; increase version.' }
  Copy-Item -LiteralPath $apk -Destination $finalApk
  Copy-Item -LiteralPath $aab -Destination $finalAab
  $metadata = [ordered]@{
    packageName = $package
    versionCode = $identity.Groups['code'].Value
    versionName = $identity.Groups['name'].Value
    signingCertificateSha256 = $fingerprint
    apk = [ordered]@{ file = [IO.Path]::GetFileName($finalApk); sha256 = Get-Sha256 $finalApk }
    aab = [ordered]@{ file = [IO.Path]::GetFileName($finalAab); sha256 = Get-Sha256 $finalAab }
    certificateDer = [IO.Path]::GetFileName($certDer)
    certificatePem = [IO.Path]::GetFileName($certPem)
    registrationProofIncluded = [bool]$RegistrationProperties
  }
  $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutputDirectory ($releaseName + '.json')) -Encoding UTF8
  Write-Host "Verified Smootify release $($identity.Groups['name'].Value) ($package)"
  Write-Host "Signing certificate SHA-256: $fingerprint"
  Write-Host "Artifacts and public certificate: $OutputDirectory"
} finally {
  foreach ($name in @('SMOOTIFY_SIGNING_SECRET', 'SMOOTIFY_KEYSTORE_PATH',
      'SMOOTIFY_KEYSTORE_PASSWORD', 'SMOOTIFY_KEY_ALIAS', 'SMOOTIFY_KEY_PASSWORD')) {
    Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
  }
  if ($plainBytes) { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
  if ($passwordBytes) { [Array]::Clear($passwordBytes, 0, $passwordBytes.Length) }
  if ($proofCopied) { Remove-Item -LiteralPath $proofTarget -Force }
  if ($createdProofDirectory -and (Test-Path $proofDirectory) -and
      -not (Get-ChildItem -LiteralPath $proofDirectory -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $proofDirectory
  }
}
