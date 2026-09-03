#!/bin/bash
# Builds the wallhub-rust depot core (kSteam + Rust hybrid migration, Phase 2/3).
#
# Phase-appropriate behavior:
#   * Always: host build + unit tests (no NDK required).
#   * When ANDROID_NDK_HOME is set: cross-compiles release .so libraries for the four
#     Android ABIs into app/src/main/jniLibs/ for the JNI bridge (WallHubRust.kt).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$SCRIPT_DIR/../wallhub-rust"
JNI_LIBS="$SCRIPT_DIR/../app/src/main/jniLibs"
MIN_SDK_API=26

cd "$CRATE_DIR"

echo "==> Host tests (cargo test)"
if [[ "${WALLHUB_RUST_SKIP_TESTS:-0}" == "1" ]]; then
    echo "==> WALLHUB_RUST_SKIP_TESTS=1; skipping host tests."
else
    cargo test --locked 2>/dev/null || cargo test
fi

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "==> ANDROID_NDK_HOME not set; skipping Android cross-compilation."
    exit 0
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_TAG="windows-x86_64"
        LINKER_SUFFIX=".cmd"
        AR_SUFFIX=".exe"
        ;;
    Darwin*)
        HOST_TAG="darwin-x86_64"
        LINKER_SUFFIX=""
        AR_SUFFIX=""
        ;;
    *)
        HOST_TAG="linux-x86_64"
        LINKER_SUFFIX=""
        AR_SUFFIX=""
        ;;
esac

TOOLCHAIN_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -x "$TOOLCHAIN_BIN/aarch64-linux-android${MIN_SDK_API}-clang$LINKER_SUFFIX" ]] || {
    echo "ERROR: NDK toolchain not found under $TOOLCHAIN_BIN" >&2
    exit 1
}

rustup target add \
    aarch64-linux-android \
    armv7-linux-androideabi \
    i686-linux-android \
    x86_64-linux-android

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/aarch64-linux-android${MIN_SDK_API}-clang$LINKER_SUFFIX"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN_BIN/armv7a-linux-androideabi${MIN_SDK_API}-clang$LINKER_SUFFIX"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/i686-linux-android${MIN_SDK_API}-clang$LINKER_SUFFIX"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN_BIN/x86_64-linux-android${MIN_SDK_API}-clang$LINKER_SUFFIX"
export CC_aarch64_linux_android="$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"
export CC_armv7_linux_androideabi="$CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER"
export CC_i686_linux_android="$CARGO_TARGET_I686_LINUX_ANDROID_LINKER"
export CC_x86_64_linux_android="$CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"
export AR_aarch64_linux_android="$TOOLCHAIN_BIN/llvm-ar$AR_SUFFIX"
export AR_armv7_linux_androideabi="$TOOLCHAIN_BIN/llvm-ar$AR_SUFFIX"
export AR_i686_linux_android="$TOOLCHAIN_BIN/llvm-ar$AR_SUFFIX"
export AR_x86_64_linux_android="$TOOLCHAIN_BIN/llvm-ar$AR_SUFFIX"

for TARGET in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
    echo "==> Building release for $TARGET"
    cargo build --release --locked --target "$TARGET" 2>/dev/null || cargo build --release --target "$TARGET"
done

declare -A ABI_BY_TARGET=(
    [aarch64-linux-android]=arm64-v8a
    [armv7-linux-androideabi]=armeabi-v7a
    [i686-linux-android]=x86
    [x86_64-linux-android]=x86_64
)

for TARGET in "${!ABI_BY_TARGET[@]}"; do
    ABI="${ABI_BY_TARGET[$TARGET]}"
    DEST="$JNI_LIBS/$ABI"
    mkdir -p "$DEST"
    cp "target/$TARGET/release/libwallhub_rust.so" "$DEST/"
    echo "==> Installed $DEST/libwallhub_rust.so"
done

echo "==> Done."
