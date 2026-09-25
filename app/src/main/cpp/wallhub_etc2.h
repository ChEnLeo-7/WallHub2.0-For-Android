// WallHub's own ETC2 RGBA8 encoder entry point.
//
// The encoder itself is etcpak (see `etcpak/`); this header is the one boundary
// between the app's Kotlin code and it. It is deliberately independent of JNI so
// the same code can be compiled into a host test binary.

#ifndef WALLHUB_ETC2_H
#define WALLHUB_ETC2_H

#include <cstddef>
#include <cstdint>

// Encodes an RGBA8 image as ETC2 RGBA8.
//
//   rgba    width * height * 4 bytes in R, G, B, A order (the order the Kotlin
//           caller already builds from Bitmap.getPixels).
//   width   multiple of 4.
//   height  multiple of 4.
//   output  width * height bytes: 16 bytes per 4x4 block, in the order a TEX
//           payload expects (block rows top to bottom, blocks left to right).
//
// The function performs no allocation of its own beyond one scratch copy of the
// input; the caller owns both buffers. Invalid arguments are a caller bug and
// terminate the process rather than produce a partial texture.
extern "C" void wallhub_etc2_compress_rgba(
    const uint8_t* rgba,
    size_t width,
    size_t height,
    uint8_t* output);

#endif
