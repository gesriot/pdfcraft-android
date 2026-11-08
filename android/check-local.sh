#!/bin/sh
# Focused host checks for the Android adapter; device testing is separate.
set -eu
PDFCRAFT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$PDFCRAFT_ROOT"
sh android/cargo-local.sh fmt --all -- --check
sh android/cargo-local.sh test --locked -p pdfcraft-android-bridge --lib -j 2 \
    --config 'profile.dev.package."*".opt-level=0' \
    --config 'profile.test.opt-level=0' --config 'profile.test.debug=0'
sh android/cargo-local.sh clippy --locked -p pdfcraft-android-bridge --lib --tests --no-deps -j 2 \
    --config 'profile.dev.package."*".opt-level=0' \
    --config 'profile.dev.opt-level=0' --config 'profile.dev.debug=0' -- -D warnings

# Shared Android storage does not preserve Unix file ownership. Limit the trust
# exception to this command and this checkout, never global Git configuration.
export GIT_CONFIG_COUNT=2
export GIT_CONFIG_KEY_0=safe.directory
export GIT_CONFIG_VALUE_0="$PDFCRAFT_ROOT"
export GIT_CONFIG_KEY_1=safe.directory
export GIT_CONFIG_VALUE_1=$(pwd -P)
for check in layers assets; do
    sh android/cargo-local.sh run --locked -p xtask -j 2 \
        --config 'profile.dev.package."*".opt-level=0' \
        --config 'profile.dev.opt-level=0' --config 'profile.dev.debug=0' -- "$check"
done
