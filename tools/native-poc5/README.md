# Native PoC5 build from the source ZIP

The source ZIP intentionally omits downloaded native dependencies, CMake/Ninja,
the Android SDK/NDK and build outputs. On Windows x64, install Python 3.12+
with pip and use PowerShell from the unpacked PoC root:

    py -3.12 ./tools/native-poc5/bootstrap.py
    & './tools/native-poc5/build-native.ps1'

bootstrap.py downloads the exact dependencies below, verifies SHA-256 before
extraction, and rejects archive paths outside their expected commit directory,
links, devices and partially extracted trees. It installs official NDK r28c in
tools/android-ndk-r28c and CMake 4.4.4 plus Ninja 1.13.2 from pinned pip
versions into tools/native-poc5/pytools. Downloads and tool installations stay
inside the unpacked workspace; a working HTTPS connection and several GB of
free disk space are required. Python pip must be available. Run bootstrap.py
again to verify the local pins and prerequisites without redownloading them.

Pinned source tarballs, from the MQVPN v0.17.0 Git tree and its nested xquic
submodule:

| Component | Exact archive URL | SHA-256 |
| --- | --- | --- |
| xquic 5f63f07e31fd3fac027d77713789c068f2e794d5 | https://codeload.github.com/mp0rta/xquic/tar.gz/5f63f07e31fd3fac027d77713789c068f2e794d5 | 45be3867b473f48cfb49c0f3de15b68c3a69bc601e034da1ddd0ad3e5b20be20 |
| lwIP 821af1994c4dbaf1e2cba9a7c2207303b3e5327b | https://codeload.github.com/mp0rta/heiher-lwip/tar.gz/821af1994c4dbaf1e2cba9a7c2207303b3e5327b | 5610359fbaabccf8d37a30ab6fdb0840e890018389e0123e3cd102bd779d03ea |
| BoringSSL 0ce57bbf06a35864fa39e3b9346d41e833683383 | https://codeload.github.com/google/boringssl/tar.gz/0ce57bbf06a35864fa39e3b9346d41e833683383 | d3599f7beb298e220c3fba884f1cd54f18af1dcc1b9b09ffe6731736746fb952 |

The same hashes and archive sizes are machine-readable in archives.sha256.json.
The BoringSSL source archive is about 75 MB. The bootstrap expects the
archive root names xquic-5f63f07..., heiher-lwip-821af199... and
boringssl-0ce57bb...; do not rename extracted directories.

Official Android NDK r28c for Windows:
https://dl.google.com/android/repository/android-ndk-r28c-windows.zip
(748,118,221 bytes; SHA-1
086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d, from the official package).
The helper deletes the verified NDK ZIP after extraction to save space. It
checks Pkg.Revision 28.2.13676358, the CMake toolchain and clang.exe. The
native build targets arm64-v8a, Android API 26, using that toolchain. It does
not need Android Studio. Building and signing the Android APK is a separate
Gradle step and requires the Android SDK described in tools/BUILD_TOOLCHAIN.md.

build-native.ps1 verifies the pinned source archives again, then builds
BoringSSL, xquic, MQVPN/lwIP and a monolithic JNI library. Its output is
tools/native-poc5/out/libmqvpn_jni.so; it does not replace the library in
upstream/android/sdk-native/src/main/jniLibs or run Gradle/ADB. Build output is
logged to tools/native-poc5/logs/native-build.log. For this PoC, the staged JNI must be
reviewed, installed into the Android project and tested on a device before
distribution. No VPS credentials or private certificates belong in this
bootstrap or its source archive.
