#!/bin/sh
# ARM64 Linux clang + official NDK target sysroot; no Termux-private link paths.
set -eu
ANDROID_NDK_HOME=${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the NDK root}
ANDROID_API=${ANDROID_API:-26}
ANDROID_SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
ANDROID_CLANG=${ANDROID_CLANG:-/usr/bin/clang}
ANDROID_RESOURCE_DIR=${ANDROID_RESOURCE_DIR:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21}
ANDROID_CLANG_HEADERS=${ANDROID_CLANG_HEADERS:-$("$ANDROID_CLANG" -print-resource-dir)/include}
if [ ! -d "$ANDROID_SYSROOT/usr/lib/aarch64-linux-android/$ANDROID_API" ]; then
    echo "Android sysroot/API directory is missing" >&2
    exit 1
fi
exec "$ANDROID_CLANG" \
    --sysroot="$ANDROID_SYSROOT" -resource-dir "$ANDROID_RESOURCE_DIR" \
    -nobuiltininc -isystem "$ANDROID_CLANG_HEADERS" \
    -L"$ANDROID_RESOURCE_DIR/lib/linux/aarch64" -Wno-unused-command-line-argument \
    -fuse-ld=lld --rtlib=compiler-rt --unwindlib=libunwind \
    -Wl,--threads=1 -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
    "$@" --target="aarch64-linux-android$ANDROID_API"
