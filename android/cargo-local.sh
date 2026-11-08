#!/bin/sh
# Explicit local build environment. Do not change the user's global toolchain.
set -eu
PDFCRAFT_BUILD_ROOT=${PDFCRAFT_BUILD_ROOT:-/tmp/pdfcraft-build}
# Also cover direct cargo-local.sh calls and host checks, outside build-local.sh.
export TMPDIR="$PDFCRAFT_BUILD_ROOT/tmp"
mkdir -p "$TMPDIR"
export CARGO_HOME="$PDFCRAFT_BUILD_ROOT/cargo"
export RUSTUP_HOME="$PDFCRAFT_BUILD_ROOT/rustup"
export CARGO_TARGET_DIR="$PDFCRAFT_BUILD_ROOT/target-linux"
export CARGO_BUILD_JOBS=2
export PATH="$CARGO_HOME/bin:$PATH"
export CC=/usr/bin/clang
export CXX=/usr/bin/clang++
if [ ! -x "$CARGO_HOME/bin/rustup" ]; then
    echo "Isolated Rust toolchain is missing; see android/ENVIRONMENT.md" >&2
    exit 1
fi
exec "$CARGO_HOME/bin/cargo" +1.95.0 "$@"
