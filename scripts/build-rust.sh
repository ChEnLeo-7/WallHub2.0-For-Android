#!/bin/bash
# Builds the wallhub-rust depot core (kSteam + Rust hybrid migration, Phase 2).
#
# Phase-appropriate behavior:
#   * Always: host build + unit tests (no NDK required).
#   * When ANDROID_NDK_HOME is set: cross-compiles release .so libraries for the four
#     Android ABIs into app/src/main/jniLibs/ for UniFFI consumption (Phase 3).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$SCRIPT_DIR/../wallhub-rust"
JNI_LIBS="$SCRIPT_DIR/../app/src/main/jniLibs"

cd "$CRATE_DIR"

echo "==> Host tests (cargo test)"
cargo test --locked 2>/dev/null || cargo test

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "==> ANDROID_NDK_HOME not set; skipping Android cross-compilation."
    exit 0
fi

command -v cargo-ndk >/dev/null 2>&1 || {
    echo "==> Installing cargo-ndk"
    cargo install cargo-ndk --locked
}

echo "==> Cross-compiling Android libraries with cargo-ndk"
cargo ndk -o "$JNI_LIBS" --platform 26 build --release

echo "==> Done. Libraries written to $JNI_LIBS"
