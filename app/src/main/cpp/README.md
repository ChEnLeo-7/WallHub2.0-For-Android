# App-owned native modules

This directory builds into the APK as `libwallhub_etc2.so`. It is compiled by
`scripts/build-etc2.sh` with the Android NDK and copied to
`app/src/main/jniLibs/<abi>/`, which is the same publication mechanism the project
used before this module existed. There is no Gradle `externalNativeBuild` step:
the build worker's SDK has no CMake package, and the NDK clang toolchain is
already the one every Android artifact here is built with.

## `etcpak/`

Vendored from [etcpak](https://github.com/wolfpld/etcpak) by Bartosz Taudul,
BSD-3-Clause (`etcpak/ETCPAK-LICENSE.txt`), via `wallhub-rust/native/` — the
product-local depot core that used to own it. Files are byte-identical to that
copy; the depot core and its license notice are otherwise unrelated to texture
compression and were removed with it.

Only the ETC2 RGBA8 entry point is used. `ProcessRGB.cpp` also carries the ETC1,
ETC2 RGB and EAC encoders; the linker drops them (`--gc-sections`) because
`wallhub_etc2.cpp` references just `CompressEtc2Rgba`.

## Files here

| File | Role |
| --- | --- |
| `wallhub_etc2.h` | The one C entry point, free of JNI so a host test can link it. |
| `wallhub_etc2.cpp` | Pixel-word conversion, block math, call into etcpak. |
| `wallhub_etc2_jni.cpp` | JNI bridge for `com.wallhub.prototype.mpkg.Etc2Compressor`. |

The Kotlin side assembles each pixel as R, G, B, A bytes; etcpak reads 32-bit
words as `0xAARRGGBB`. `wallhub_etc2.cpp` owns that permutation, which the
removed `wallhub-rust` JNI layer applied per pixel before calling the same
encoder function with the same `useHeuristics = true`.
