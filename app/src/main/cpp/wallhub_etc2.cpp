#include "wallhub_etc2.h"

#include <cstring>
#include <limits>
#include <vector>

#include "etcpak/ProcessRGB.hpp"

namespace {

// etcpak reads each pixel as one 32-bit word laid out 0xAARRGGBB: it takes
// `v >> 24` for alpha, `(v >> 16) & 0xff` for red, `(v >> 8) & 0xff` for green
// and `v & 0xff` for blue. The Kotlin caller hands over plain R, G, B, A bytes,
// so the words are assembled here. The previous bridge (the removed
// `wallhub-rust` JNI layer) performed exactly this permutation per pixel.
inline uint32_t to_encoder_word(const uint8_t* pixel) {
    return (static_cast<uint32_t>(pixel[3]) << 24) | (static_cast<uint32_t>(pixel[0]) << 16) |
           (static_cast<uint32_t>(pixel[1]) << 8) | static_cast<uint32_t>(pixel[2]);
}

}  // namespace

extern "C" void wallhub_etc2_compress_rgba(
    const uint8_t* rgba,
    size_t width,
    size_t height,
    uint8_t* output) {
    // A caller that gets here with invalid arguments has a bug, but this runs on
    // a texture the user is waiting for and the Kotlin side validates first, so
    // the native side refuses quietly instead of taking the process down.
    if (rgba == nullptr || output == nullptr) {
        return;
    }
    if (width == 0 || height == 0 || width % 4 != 0 || height % 4 != 0) {
        return;
    }
    if (height > std::numeric_limits<size_t>::max() / width) {
        return;  // Dimension overflow: refuse rather than under-allocate.
    }
    const size_t pixels = width * height;

    std::vector<uint32_t> words(pixels);
    for (size_t index = 0; index < pixels; ++index) {
        words[index] = to_encoder_word(rgba + index * 4);
    }

    // One 4x4 block is 16 bytes, so the encoder writes exactly `pixels` bytes
    // through aligned 8-byte units; `output` itself carries no such guarantee.
    const size_t block_count = pixels / 16;
    std::vector<uint64_t> blocks(pixels / 8);
    CompressEtc2Rgba(
        words.data(),
        blocks.data(),
        static_cast<uint32_t>(block_count),
        width,
        true);
    std::memcpy(output, blocks.data(), pixels);
}
