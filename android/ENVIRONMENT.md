# Build on Ubuntu ARM64 in Termux/proot

Run these commands in an **Ubuntu 26.04 ARM64** shell inside proot-distro, as root,
not in the outer Termux shell. Keep the checkout and build directories on Ubuntu's
Linux filesystem, not Android shared storage. Run the build commands from the
repository root. The setup commands below are for a fresh environment; keep an
existing working SDK/toolchain instead of overwriting it.

## 1. Choose persistent build storage

```sh
set -eu
export PDFCRAFT_BUILD_ROOT="$HOME/.local/share/pdfcraft-build"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export ANDROID_AAPT2="$HOME/android-arm-tools/aapt2"
export TMPDIR="$PDFCRAFT_BUILD_ROOT/tmp"
mkdir -p "$PDFCRAFT_BUILD_ROOT/downloads" "$TMPDIR"
df -hT "$PDFCRAFT_BUILD_ROOT" /tmp
```

Check that the chosen directory is on disk and has room for the SDK, NDK, Cargo
sources, host and Android compilation outputs. `/tmp` can be a small tmpfs in
proot: it can fill even when the tablet has free storage. Do not put a fresh build
there. The shell wrappers set `TMPDIR` to `$PDFCRAFT_BUILD_ROOT/tmp`, including for
direct Cargo calls and Android-native tests. This directs Rust temporary files
onto the chosen filesystem; it does not configure Java's `java.io.tmpdir` property.

Use the same `PDFCRAFT_BUILD_ROOT` in later shell sessions. It selects both the
isolated Rust installation and build outputs. The scripts retain `/tmp/pdfcraft-build`
as a compatibility default for older installations, so set the variable explicitly.
To move an existing setup, preserve its `cargo/` and `rustup/` directories together,
or reinstall the toolchain in the new location using step 4.

## 2. Install Ubuntu host tools

```sh
apt-get update
apt-get install -y ca-certificates curl git unzip zip file python3 \
  build-essential cmake ninja-build pkg-config perl \
  clang lld llvm-21 openjdk-21-jdk-headless qemu-user \
  libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross

/usr/bin/clang --version
/usr/bin/clang++ --version
/usr/bin/ld.lld --version
/usr/bin/llvm-ar-21 --version
/usr/bin/llvm-strip-21 --version
java -version
qemu-x86_64 --version
```

Enable Ubuntu's `universe` component if APT cannot find these packages. On
[Ubuntu 26.04, the `clang` metapackage](https://packages.ubuntu.com/resolute/clang)
selects Clang 21 and supplies `/usr/bin/clang` and `/usr/bin/clang++`.
Installing only `clang-21` does not supply those unversioned names, which
`cargo-local.sh` uses. Keep Clang/LLVM 21 for this recipe: the linker wrapper uses
the NDK r29 LLVM 21 runtime directory. `lld` supplies the unversioned linker;
`llvm-21` supplies the versioned archiver and strip tool.

The `*-amd64-cross` packages provide x86-64 runtime libraries for QEMU. They do
not change Ubuntu's ARM64 host architecture or become Android APK dependencies.
JDK 21 runs natively on ARM64. Gradle 8.14.3 is downloaded by the committed Wrapper;
no system Gradle installation is needed.

## 3. Install the SDK and NDK

### Android SDK

Download Google's command-line tools. The URL and SHA-256 below identify the Linux
package from the [official download page](https://developer.android.com/studio#command-line-tools-only).
Review the SDK terms there and the licences presented by `sdkmanager`.

```sh
(
  cd "$PDFCRAFT_BUILD_ROOT/downloads"
  curl --fail --location --retry 3 \
    https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
    -o commandlinetools-linux-15859902_latest.zip
  printf '%s  %s\n' \
    4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 \
    commandlinetools-linux-15859902_latest.zip | sha256sum --check
  mkdir -p sdk-tools-unpack
  unzip -q commandlinetools-linux-15859902_latest.zip -d sdk-tools-unpack
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  test ! -e "$ANDROID_HOME/cmdline-tools/latest"
  mv sdk-tools-unpack/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
)

"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0"
```

[`sdkmanager`](https://developer.android.com/tools/sdkmanager) runs on the host JDK.
Platform 36 supplies the compile SDK. AGP 8.10.1 uses Build Tools 35.0.0 by default;
36.0.0 supplies the aapt2 binary used in step 5. Linux SDK executables such as aapt2
are x86-64, so do not execute them directly on the ARM64 host.

### Android NDK r29

The [official NDK r29 release](https://github.com/android/ndk/releases/tag/r29)
is revision **29.0.14206865**. Its Linux ZIP is **783549481 bytes**, with published
SHA-1 **87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b**. The archive expands to
`android-ndk-r29/`; move that directory to the revision path expected by the scripts.

```sh
(
  cd "$PDFCRAFT_BUILD_ROOT/downloads"
  curl --fail --location --retry 3 \
    https://dl.google.com/android/repository/android-ndk-r29-linux.zip \
    -o android-ndk-r29-linux.zip
  test "$(wc -c < android-ndk-r29-linux.zip)" -eq 783549481
  printf '%s  %s\n' \
    87e2bb7e9be5d6a1c6cdf5ec40dd4e0c6d07c30b \
    android-ndk-r29-linux.zip | sha1sum --check
  unzip -q android-ndk-r29-linux.zip
  mkdir -p "$ANDROID_HOME/ndk"
  test ! -e "$ANDROID_NDK_HOME"
  mv android-ndk-r29 "$ANDROID_NDK_HOME"
)

test -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/26"
test -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21"
```

The NDK directory contains x86-64 host executables. The build uses its Android
sysroot, compiler-rt and libunwind with **Ubuntu ARM64 clang**, not the NDK's
x86-64 clang. Do not link against Termux-private libraries. Keep the wrapper's
host intrinsic headers, API-qualified target argument, single linker thread and
16 KiB page-alignment flags.

## 4. Install the isolated Rust toolchain

Install the Linux ARM64 host toolchain, not Termux's Android-host Rust. These
locations match `cargo-local.sh`; global Rust configuration is not changed.
The installer and its checksum come from the
[official rustup distribution](https://rust-lang.github.io/rustup/installation/other.html#manual-installation).

```sh
export CARGO_HOME="$PDFCRAFT_BUILD_ROOT/cargo"
export RUSTUP_HOME="$PDFCRAFT_BUILD_ROOT/rustup"
(
  cd "$PDFCRAFT_BUILD_ROOT/downloads"
  curl --fail --location --retry 3 \
    https://static.rust-lang.org/rustup/dist/aarch64-unknown-linux-gnu/rustup-init \
    -o rustup-init
  curl --fail --location --retry 3 \
    https://static.rust-lang.org/rustup/dist/aarch64-unknown-linux-gnu/rustup-init.sha256 \
    -o rustup-init.sha256
  sha256sum --check rustup-init.sha256
  chmod 700 rustup-init
  ./rustup-init -y --no-modify-path --profile minimal \
    --default-host aarch64-unknown-linux-gnu --default-toolchain 1.95.0
)
"$CARGO_HOME/bin/rustup" component add --toolchain 1.95.0 rustfmt clippy
"$CARGO_HOME/bin/rustup" target add --toolchain 1.95.0 aarch64-linux-android
"$CARGO_HOME/bin/rustc" +1.95.0 --version --verbose
sh android/cargo-local.sh --version
```

The `rustc` host line must be `aarch64-unknown-linux-gnu`, and the compiler version
must be 1.95.0. `cargo-local.sh` invokes this installation by absolute path and
selects `+1.95.0`; another Rust installation in the Termux PATH is not used.

## 5. Run the official SDK aapt2 through QEMU

Create a separate x86-64 runtime root from Ubuntu's cross-library packages. The
loader must exist at `lib64/ld-linux-x86-64.so.2` beneath this root; `libc`, `libm`,
`libgcc_s` and the C++ runtime go in its x86-64 library directory.

```sh
mkdir -p /opt/x86_64-sysroot/lib/x86_64-linux-gnu /opt/x86_64-sysroot/lib64
cp -a /usr/x86_64-linux-gnu/lib/. /opt/x86_64-sysroot/lib/x86_64-linux-gnu/
ln -sfn ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 \
  /opt/x86_64-sysroot/lib64/ld-linux-x86-64.so.2
test -f /opt/x86_64-sysroot/lib64/ld-linux-x86-64.so.2
```

Create an executable wrapper outside the repository. A clean SDK names the ELF
binary `aapt2`. If an existing SDK has already wrapped that path and renamed the
binary to `aapt2.real`, this wrapper selects the real file explicitly. Do not make
a symlink to an SDK wrapper that calls `$0.real`: the new symlink location would
change the path it tries to execute.

```sh
mkdir -p "$HOME/android-arm-tools"
cat > "$ANDROID_AAPT2" <<'AAPT2_WRAPPER'
#!/bin/sh
set -eu
export QEMU_LD_PREFIX=${QEMU_LD_PREFIX:-/opt/x86_64-sysroot}
PDFCRAFT_AAPT2_BINARY="${ANDROID_HOME:-$HOME/android-sdk}/build-tools/36.0.0/aapt2"
if [ -f "$PDFCRAFT_AAPT2_BINARY.real" ]; then
    PDFCRAFT_AAPT2_BINARY="$PDFCRAFT_AAPT2_BINARY.real"
fi
exec /usr/bin/qemu-x86_64 -E LD_LIBRARY_PATH=/lib/x86_64-linux-gnu \
    "$PDFCRAFT_AAPT2_BINARY" "$@"
AAPT2_WRAPPER
chmod 700 "$ANDROID_AAPT2"
"$ANDROID_AAPT2" version
```

The last command must print an aapt2 version before starting the full build.
[QEMU user-mode emulation](https://www.qemu.org/docs/master/user/main.html) runs this
host tool only; it is not part of the APK. `QEMU_LD_PREFIX` supplies the x86-64
interpreter root, and `-E` sets the guest library path without loading x86-64
libraries into the ARM64 QEMU process. If libraries are missing, inspect the real
binary with `readelf -d` and supply the matching x86-64 runtime libraries, not
ARM64 `.so` files. Keep this runtime separate from the Android NDK sysroot.

## 6. Build debug and release

In a new Ubuntu shell, restore the same paths, then run from the repository root:

```sh
export PDFCRAFT_BUILD_ROOT="$HOME/.local/share/pdfcraft-build"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
export ANDROID_AAPT2="$HOME/android-arm-tools/aapt2"

PDFCRAFT_BUILD_TYPE=debug sh android/build-local.sh
PDFCRAFT_SIGNING_PROPERTIES="$HOME/.local/share/pdfcraft/signing.properties" \
  PDFCRAFT_BUILD_TYPE=release sh android/build-local.sh
```

Debug uses its own development certificate. For release, prepare your private
keystore and properties using [README.md](README.md#release-signing); the release
command above requires those files and never substitutes a debug key. Keep signing
material outside the checkout and preserve the key used for installed releases.

Outputs are under `$PDFCRAFT_BUILD_ROOT/android/app/outputs/apk/{debug,release}/`.
The script builds the Rust library, generates matching Kotlin bindings, strips only
the packaged library copy, and runs Gradle packaging and Kotlin unit tests. The
first build downloads Cargo Git dependencies and Gradle/Maven dependencies; allow
for their disk usage and download time. The script does not install the APK.

Use [TESTING.md](TESTING.md) for checks. Additional tool overrides are `ANDROID_AR`
(default `/usr/bin/llvm-ar-21`), `ANDROID_STRIP` (`/usr/bin/llvm-strip-21`),
`ANDROID_CLANG` (`/usr/bin/clang`), `ANDROID_API` (`26`), `ANDROID_RESOURCE_DIR`
(the NDK LLVM 21 runtime directory), and `ANDROID_CLANG_HEADERS` (headers from the
host compiler). These cross-compilation overrides do not change the host C/C++
compiler paths in `cargo-local.sh`.
