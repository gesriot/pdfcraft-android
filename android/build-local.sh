#!/bin/sh
# Android build for Ubuntu/aarch64 proot; release signing stays private.
set -eu
PDFCRAFT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PDFCRAFT_ROOT"
PDFCRAFT_BUILD_TYPE=${PDFCRAFT_BUILD_TYPE:-release}
case "$PDFCRAFT_BUILD_TYPE" in
    release) PDFCRAFT_GRADLE_VARIANT=Release ;;
    debug) PDFCRAFT_GRADLE_VARIANT=Debug ;;
    *) echo "PDFCRAFT_BUILD_TYPE must be release or debug" >&2; exit 1 ;;
esac
PDFCRAFT_BUILD_ROOT=${PDFCRAFT_BUILD_ROOT:-/tmp/pdfcraft-build}
export PDFCRAFT_BUILD_ROOT
# proot may mount /tmp as a small tmpfs; rustc must use the selected build storage.
export TMPDIR="$PDFCRAFT_BUILD_ROOT/tmp"
mkdir -p "$TMPDIR"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.14206865}
ANDROID_AAPT2=${ANDROID_AAPT2:-$HOME/android-arm-tools/aapt2}
test -x "$ANDROID_AAPT2"
export PDFCRAFT_NATIVE_DIR="$PDFCRAFT_BUILD_ROOT/android-native"
export PDFCRAFT_ANDROID_BUILD_DIR="$PDFCRAFT_BUILD_ROOT/android"
mkdir -p "$PDFCRAFT_BUILD_ROOT/bin" "$PDFCRAFT_NATIVE_DIR/jniLibs/arm64-v8a" "$PDFCRAFT_NATIVE_DIR/kotlin"
if ! cmp -s android/clang-android.sh "$PDFCRAFT_BUILD_ROOT/bin/clang-android"; then
    cp android/clang-android.sh "$PDFCRAFT_BUILD_ROOT/bin/clang-android"
    chmod 700 "$PDFCRAFT_BUILD_ROOT/bin/clang-android"
fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$PDFCRAFT_BUILD_ROOT/bin/clang-android"
export CC_aarch64_linux_android="$PDFCRAFT_BUILD_ROOT/bin/clang-android"
export AR_aarch64_linux_android=${ANDROID_AR:-/usr/bin/llvm-ar-21}
test -x "$AR_aarch64_linux_android"

# Optimized release code. Avoid whole-program LTO on the phone to bound memory.
timeout 3600 sh android/cargo-local.sh build --locked --release -p pdfcraft-android-bridge \
    --target aarch64-linux-android -j 2 \
    --config 'profile.release.opt-level=2' --config 'profile.release.lto=false' \
    --config 'profile.release.codegen-units=16' --config 'profile.release.debug=0'
PDFCRAFT_SO="$PDFCRAFT_BUILD_ROOT/target-linux/aarch64-linux-android/release/libpdfcraft_android_bridge.so"
cp "$PDFCRAFT_SO" "$PDFCRAFT_NATIVE_DIR/jniLibs/arm64-v8a/"

timeout 1800 sh android/cargo-local.sh run --locked -p pdfcraft-android-bridge \
    --features bindgen --bin pdfcraft-bindgen -j 2 \
    --config 'profile.dev.package."*".opt-level=0' \
    --config 'profile.dev.opt-level=0' --config 'profile.dev.debug=0' -- \
    generate --library "$PDFCRAFT_SO" --language kotlin --metadata-no-deps --no-format \
    --crate pdfcraft_android_bridge --out-dir "$PDFCRAFT_NATIVE_DIR/kotlin"

# Gradle's stock NDK strip executable is x86-64. Strip only the packaged copy
# with the ARM64 host tool; keep the original .so for bindgen and debugging.
ANDROID_STRIP=${ANDROID_STRIP:-/usr/bin/llvm-strip-21}
test -x "$ANDROID_STRIP"
"$ANDROID_STRIP" --strip-unneeded "$PDFCRAFT_NATIVE_DIR/jniLibs/arm64-v8a/libpdfcraft_android_bridge.so"

timeout 900 sh android/gradlew -p android --no-daemon \
    --project-cache-dir "$PDFCRAFT_BUILD_ROOT/gradle-project" \
    "-Pandroid.aapt2FromMavenOverride=$ANDROID_AAPT2" \
    ":app:assemble$PDFCRAFT_GRADLE_VARIANT" ":app:test${PDFCRAFT_GRADLE_VARIANT}UnitTest"
printf '\nAPK: %s\n' "$PDFCRAFT_ANDROID_BUILD_DIR/app/outputs/apk/$PDFCRAFT_BUILD_TYPE/app-$PDFCRAFT_BUILD_TYPE.apk"
