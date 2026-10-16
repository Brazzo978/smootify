# Android release signing

Smootify's stable application ID is **com.codmanu.smootify**. A different ID is
a different installed app. Updates to an installation must retain its signing
identity. Smootify uses a dedicated release key; LLE's key is not reused.

## Local release key and APK

Run from the repository root on Windows:

```powershell
powershell -NoProfile -File .\tools\android-release\Invoke-SmootifyRelease.ps1 -InitializeKey
```

Use `-InitializeKey` only once. It creates an RSA 3072-bit PKCS#12 key outside the
repository and protects the random password with Windows DPAPI CurrentUser.
Later builds omit that flag. The script refuses to replace an existing key.

The `.p12` and `.dpapi` files must both be kept securely. DPAPI data is tied to
the Windows user and does not automatically transfer to another account or PC.
Maintain an encrypted, recoverable backup and test recovery before depending on
this identity. Never put the key or password in Git or GitHub release assets.

The script builds release APK/AAB files, verifies the APK package, signer and
non-debuggable flag, verifies the bundle signer, then exports artifacts, SHA-256
metadata and the public DER/PEM certificate. It refuses to overwrite an existing
artifact with the same version. `-JdkPath`, `-SdkPath`, `-PrivateDirectory` and
`-OutputDirectory` select the tools and output locations; private material and
release output must stay outside this repository.

Only the public certificate belongs in
[distribution/android](../distribution/android). Its SHA-256 fingerprint must
match the one printed by `apksigner verify --print-certs` for distributed APKs.
Building or signing an APK does not publish it to Google Play.

## Optional Google registration

Developer verification associates the package and signing certificate with a
verified developer account. It is separate from APK signing and from store
publication. Check the console's actual status before claiming registration.
A new package may require its public certificate. An existing package may require
a signed ownership proof containing the console's `adi-registration.properties`.

For that challenge, save the file outside the repository and run:

```powershell
powershell -NoProfile -File .\tools\android-release\Invoke-SmootifyRelease.ps1 `
  -RegistrationProperties 'C:\private-path\adi-registration.properties'
```

The helper includes the proof only in the release build, removes the temporary
source file afterward, and names those artifacts with `-registration`. Upload
that proof only to the console; do not publish it as a normal release.

See [Google's developer verification guide](https://developer.android.com/developer-verification/guides/google-play-console)
and [package registration help](https://support.google.com/googleplay/android-developer/answer/16761053?hl=en).

## If Play distribution is enabled later

Play distinguishes its **app signing key**, which signs delivered APKs, from the
**upload key**, which authenticates uploads. To keep Play and direct-download APKs
compatible, arrange for the same delivered signing identity: import the project's
app signing key into Play using its documented secure PEPK process, or distribute
the universal APK signed by Play. A local APK signed only with a separate upload
key cannot update a Play-signed installation.

See [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en).
No Play submission or publication is performed by the release script.
