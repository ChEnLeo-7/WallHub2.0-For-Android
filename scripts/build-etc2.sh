#!/bin/bash
# Builds the app-owned ETC2 RGBA8 encoder (app/src/main/cpp) into
# app/src/main/jniLibs/<abi>/libwallhub_etc2.so for every shipped ABI.
#
# This replaces the native half of the removed `wallhub-rust` depot core: the texture
# compressor stayed in the product, the depot core did not. There is no cargo step and no
# host test step — the encoder is compiled once per ABI by the NDK's clang++ and packaged
# through the standard jniLibs source set.
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk bash scripts/build-etc2.sh
#
# NDK resolution order: $ANDROID_NDK_HOME, $ANDROID_NDK_ROOT, the newest
# $ANDROID_HOME/ndk/*, then the newest $ANDROID_SDK_ROOT/ndk/*.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$REPO_DIR/app/src/main/cpp"
JNI_LIBS="$REPO_DIR/app/src/main/jniLibs"
MIN_SDK_API=26

resolve_ndk() {
    local candidate
    for candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    local sdk_root
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -n "$sdk_root" && -d "$sdk_root/ndk" ]] || continue
        candidate="$(ls -1d "$sdk_root"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

NDK_HOME="$(resolve_ndk)" || {
    echo "ERROR: no Android NDK found. Set ANDROID_NDK_HOME (or ANDROID_HOME with an ndk/ tree)." >&2
    exit 1
}

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        HOST_TAG="windows-x86_64"
        TOOL_SUFFIX=".cmd"
        STRIP_SUFFIX=".exe"
        ;;
    Darwin*)
        # darwin-x86_64 binaries run on Apple silicon under Rosetta; the NDK also
        # ships a darwin-arm64 prebuilt on newer releases.
        if [[ -d "$NDK_HOME/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
            HOST_TAG="darwin-arm64"
        else
            HOST_TAG="darwin-x86_64"
        fi
        TOOL_SUFFIX=""
        STRIP_SUFFIX=""
        ;;
    *)
        HOST_TAG="linux-x86_64"
        TOOL_SUFFIX=""
        STRIP_SUFFIX=""
        ;;
esac

TOOLCHAIN_BIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"
# -f rather than -x: MSYS2/Git Bash does not report the execute bit for the NDK's
# .cmd wrappers on NTFS, and bash still runs them.
[[ -f "$TOOLCHAIN_BIN/aarch64-linux-android${MIN_SDK_API}-clang++$TOOL_SUFFIX" ]] || {
    echo "ERROR: NDK toolchain not found under $TOOLCHAIN_BIN" >&2
    exit 1
}

SOURCES=(
    "$SOURCE_DIR/wallhub_etc2.cpp"
    "$SOURCE_DIR/wallhub_etc2_jni.cpp"
    "$SOURCE_DIR/etcpak/ProcessRGB.cpp"
    "$SOURCE_DIR/etcpak/Dither.cpp"
    "$SOURCE_DIR/etcpak/Tables.cpp"
)

COMMON_FLAGS=(
    -O3
    -std=c++17
    -fPIC
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -DNDEBUG
)
LINK_FLAGS=(
    -shared
    -Wl,--gc-sections
    # Keep libc++ inside the library: the APK ships no libc++_shared.so and the
    # previous native library was statically linked the same way. Its symbols are
    # not re-exported, so this library cannot interpose on another one.
    -static-libstdc++
    -Wl,--exclude-libs,ALL
    -lm
)

# ABI -> NDK target triple prefix (the clang wrapper adds the API level).
declare -A TRIPLE_BY_ABI=(
    [arm64-v8a]=aarch64-linux-android
    [armeabi-v7a]=armv7a-linux-androideabi
    [x86]=i686-linux-android
    [x86_64]=x86_64-linux-android
)

# The worker keeps its checkout between jobs; never publish a stale or foreign library.
rm -rf "$JNI_LIBS"

for ABI in "${!TRIPLE_BY_ABI[@]}"; do
    TRIPLE="${TRIPLE_BY_ABI[$ABI]}"
    CXX="$TOOLCHAIN_BIN/${TRIPLE}${MIN_SDK_API}-clang++$TOOL_SUFFIX"
    DEST="$JNI_LIBS/$ABI"
    mkdir -p "$DEST"
    echo "==> Compiling libwallhub_etc2.so for $ABI"
    "$CXX" "${COMMON_FLAGS[@]}" "${LINK_FLAGS[@]}" \
        -Wl,-soname,libwallhub_etc2.so \
        -o "$DEST/libwallhub_etc2.so" \
        "${SOURCES[@]}"
    if [[ -x "$TOOLCHAIN_BIN/llvm-strip$STRIP_SUFFIX" ]]; then
        "$TOOLCHAIN_BIN/llvm-strip$STRIP_SUFFIX" --strip-unneeded "$DEST/libwallhub_etc2.so"
    fi
    echo "==> Installed $DEST/libwallhub_etc2.so"
done

echo "==> Done."
