"""Bootstrap the pinned Windows arm64 native build inputs for MQVPN PoC5.

Run with Python 3.10+ from the unpacked source tree. All files stay below the
source tree. Existing dependency trees are never overwritten.
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
TOOLS = PROJECT / "tools"
DEPS = HERE / "deps"
PINS = {
    "xquic": ("mp0rta", "xquic", "5f63f07e31fd3fac027d77713789c068f2e794d5"),
    "lwip": ("mp0rta", "heiher-lwip", "821af1994c4dbaf1e2cba9a7c2207303b3e5327b"),
    "boringssl": ("google", "boringssl", "0ce57bbf06a35864fa39e3b9346d41e833683383"),
}
NDK_URL = "https://dl.google.com/android/repository/android-ndk-r28c-windows.zip"
NDK_SHA1 = "086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d"
NDK_REVISION = "Pkg.Revision = 28.2.13676358"


def verified_download(url: str, path: pathlib.Path, algorithm: str, expected: str) -> None:
    def digest(file_path: pathlib.Path) -> str:
        with file_path.open("rb") as source:
            return hashlib.file_digest(source, algorithm).hexdigest()

    if path.exists():
        actual = digest(path)
        if actual != expected:
            raise RuntimeError(f"Checksum mismatch for existing {path}: {actual}")
        print(f"Verified cached {path.name}: {actual}", flush=True)
        return
    partial = path.with_name(path.name + ".part")
    if partial.exists():
        raise RuntimeError(f"Partial download exists; inspect before retrying: {partial}")
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "mqvpn-poc5-source-bootstrap"})
    try:
        with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as target:
            shutil.copyfileobj(response, target, length=1024 * 1024)
        actual = digest(partial)
        if actual != expected:
            raise RuntimeError(f"Checksum mismatch for {url}: expected {expected}, got {actual}")
        partial.replace(path)
    except Exception:
        # Preserve the partial file for diagnosis; never use it as a verified pin.
        raise
    print(f"Verified {path.name}: {actual}", flush=True)


def safe_parts(name: str, expected_root: str) -> tuple[str, ...]:
    if "\\" in name:
        raise RuntimeError(f"Backslash in archive member: {name}")
    p = pathlib.PurePosixPath(name)
    if p.is_absolute() or not p.parts or p.parts[0] != expected_root or ".." in p.parts:
        raise RuntimeError(f"Unsafe archive member: {name}")
    return p.parts[1:]


def extract_tar(archive: pathlib.Path, expected_root: str) -> None:
    DEPS.mkdir(exist_ok=True)
    destination = DEPS / expected_root
    sentinel = pathlib.Path("src/core/init.c") if expected_root.startswith("heiher-lwip-") else pathlib.Path("CMakeLists.txt")
    if destination.exists():
        if destination.is_symlink() or destination.is_junction():
            raise RuntimeError(f"Unexpected link at dependency destination: {destination}")
        if not (destination / sentinel).is_file():
            raise RuntimeError(f"Existing dependency tree needs inspection: {destination}")
        print(f"Existing source tree retained: {destination}", flush=True)
        return
    staging = DEPS / (expected_root + ".extracting")
    if staging.exists():
        raise RuntimeError(f"Partial extraction exists; inspect before retrying: {staging}")
    staging.mkdir()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            parts = safe_parts(member.name, expected_root)
            if not parts:
                continue
            if member.issym() or member.islnk() or member.isdev() or member.isfifo():
                raise RuntimeError(f"Link/device member rejected: {member.name}")
            target = staging.joinpath(*parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = tar.extractfile(member)
                if source is None:
                    raise RuntimeError(f"Unreadable archive member: {member.name}")
                with source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
            else:
                raise RuntimeError(f"Unexpected archive member type: {member.name}")
    if not (staging / sentinel).is_file():
        raise RuntimeError(f"Missing required source in {archive}")
    staging.rename(destination)
    print(f"Extracted {destination}", flush=True)


def prepare_ndk() -> None:
    destination = TOOLS / "android-ndk-r28c"
    properties = destination / "source.properties"
    toolchain = destination / "build" / "cmake" / "android.toolchain.cmake"
    clang = destination / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin" / "clang.exe"
    if properties.is_file() and NDK_REVISION in properties.read_text(encoding="utf-8"):
        if toolchain.is_file() and clang.is_file():
            print(f"Verified existing NDK r28c: {destination}", flush=True)
            return
    if destination.exists():
        raise RuntimeError(f"Partial or different NDK exists; inspect before retrying: {destination}")
    archive = HERE / "android-ndk-r28c-windows.zip"
    verified_download(NDK_URL, archive, "sha1", NDK_SHA1)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            parts = safe_parts(info.filename, "android-ndk-r28c")
            mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(mode):
                raise RuntimeError(f"Symlink rejected in NDK ZIP: {info.filename}")
            target = destination.joinpath(*parts)
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
    if not (properties.is_file() and NDK_REVISION in properties.read_text(encoding="utf-8")
            and toolchain.is_file() and clang.is_file()):
        raise RuntimeError("NDK extraction incomplete")
    archive.unlink()
    print(f"Installed verified NDK r28c: {destination}", flush=True)


def prepare_cmake_ninja() -> None:
    cmake = HERE / "pytools" / "cmake" / "data" / "bin" / "cmake.exe"
    ninja = HERE / "pytools" / "bin" / "ninja.exe"
    if not (cmake.is_file() and ninja.is_file()):
        if (HERE / "pytools").exists():
            raise RuntimeError("Partial pytools directory exists; inspect before retrying")
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "--no-cache-dir",
             "--only-binary=:all:", "--target", str(HERE / "pytools"),
             "cmake==4.4.4", "ninja==1.13.2"],
            check=True,
        )
    cmake_version = subprocess.check_output([str(cmake), "--version"], text=True).splitlines()[0]
    ninja_version = subprocess.check_output([str(ninja), "--version"], text=True).strip()
    if cmake_version != "cmake version 4.4.4" or not ninja_version.startswith("1.13.2"):
        raise RuntimeError(f"Unexpected CMake/Ninja: {cmake_version}; {ninja_version}")
    print(f"Verified tools: {cmake_version}, Ninja {ninja_version}", flush=True)


def main() -> None:
    if sys.platform != "win32":
        raise RuntimeError("This helper prepares the Windows NDK toolchain")
    manifest = json.loads((HERE / "archives.sha256.json").read_text(encoding="utf-8-sig"))
    for name, (owner, repo, commit) in PINS.items():
        filename = f"{name}-{commit}.tar.gz"
        pin = manifest[filename]
        archive = HERE / filename
        verified_download(
            f"https://codeload.github.com/{owner}/{repo}/tar.gz/{commit}",
            archive, "sha256", pin["sha256"],
        )
        if archive.stat().st_size != pin["bytes"]:
            raise RuntimeError(f"Unexpected archive length: {archive}")
        extract_tar(archive, f"{repo}-{commit}")
    prepare_ndk()
    prepare_cmake_ninja()
    print("Ready: powershell -File tools/native-poc5/build-native.ps1", flush=True)


if __name__ == "__main__":
    main()
