# Build the Android app

The repository contains the Android PoC8 source under `upstream/android`, including
the arm64 native tunnel engine and iperf executable used by that build. This is
an independent project based on MQVPN, not the official upstream Android app.

## Prerequisites

- JDK 25 (the current build was validated with Eclipse Adoptium 25.0.2).
- Android SDK Platform 37 and Build Tools 36.0.0; accept the SDK licenses.
- A working HTTPS connection for Gradle and Maven dependencies.

Point `ANDROID_HOME` at your SDK, or create the local-only file
`upstream/android/local.properties` containing `sdk.dir=/absolute/path/to/sdk`.
Do not commit that file. The Gradle wrapper pins Gradle 9.8.0; AGP is 9.4.1,
Kotlin 2.4.20, Hilt 2.60.1. Only **arm64-v8a**, Android API 26+, is packaged.

```bash
cd upstream/android
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug` in the same directory.
Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use your local
Android debug signing key. Release signing reads the `SMOOTIFY_KEYSTORE_PATH`,
`SMOOTIFY_KEYSTORE_PASSWORD`, `SMOOTIFY_KEY_ALIAS` and `SMOOTIFY_KEY_PASSWORD` environment
variables; without them the release APK remains unsigned. Never commit keys.

The application ID is **`com.codmanu.smootify`**, version code 51, version
`0.1.2`. This is a separate installed app from the original MQVPN PoC. Its
permissions, VPN consent and Shizuku authorization must be granted for the new
package; Android keeps the old app's private data separate. Android tunnel MTU
remains fixed at **1280**.

See [Android signing and Google registration](ANDROID_SIGNING.md) for stable
release APK/AAB builds and certificate verification.

## Native components

The JNI handover engine is built from the included MQVPN sources and pinned
xquic/lwIP/BoringSSL dependencies. The Windows bootstrap and rebuild procedure
are in [tools/native-poc5](../tools/native-poc5/README.md). Inspect and test a
rebuilt JNI before replacing the packaged library: the native build helper
intentionally does not install it into the app automatically.

[iperf build tools](../tools/iperf/README.md) rebuild the ESnet 3.18 Android
executable. Do not replace the patched JNI with an official upstream APK's JNI;
they are different builds.

MQVPN engine sources retain their upstream paths and build instructions under
`upstream/`. Third-party sources are downloaded with the pinned bootstrap rather
than vendored here. The legacy `.gitmodules` is reference metadata; the source
snapshot is not a Git checkout with initialized submodules.

Keys under `upstream/tests/certs` are public test fixtures and must never be used
for a live server. Third-party license texts accompany the packaged libraries.
