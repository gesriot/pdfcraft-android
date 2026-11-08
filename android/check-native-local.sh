#!/bin/sh
# Android-target tests executed in ARM64 proot; not installed-app/UI tests.
set -eu
PDFCRAFT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PDFCRAFT_ROOT"
PDFCRAFT_BUILD_ROOT=${PDFCRAFT_BUILD_ROOT:-/tmp/pdfcraft-build}
export PDFCRAFT_BUILD_ROOT
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/29.0.14206865}
mkdir -p "$PDFCRAFT_BUILD_ROOT/bin" "$PDFCRAFT_BUILD_ROOT/tmp"
export TMPDIR="$PDFCRAFT_BUILD_ROOT/tmp"
if ! cmp -s android/clang-android.sh "$PDFCRAFT_BUILD_ROOT/bin/clang-android"; then
    cp android/clang-android.sh "$PDFCRAFT_BUILD_ROOT/bin/clang-android"
    chmod 700 "$PDFCRAFT_BUILD_ROOT/bin/clang-android"
fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$PDFCRAFT_BUILD_ROOT/bin/clang-android"
export CC_aarch64_linux_android="$PDFCRAFT_BUILD_ROOT/bin/clang-android"
export AR_aarch64_linux_android=${ANDROID_AR:-/usr/bin/llvm-ar-21}
sh android/cargo-local.sh test --locked -p pdfcraft-android-bridge --lib \
    --target aarch64-linux-android -j 2 \
    --config 'profile.dev.package."*".opt-level=0' \
    --config 'profile.dev.opt-level=0' --config 'profile.dev.debug=0' \
    --config 'profile.test.opt-level=0' --config 'profile.test.debug=0'
