#include <cstddef>
#include <cstdint>

#include "ProcessRGB.hpp"

extern "C" void wallhub_compress_etc2_rgba(
    const uint32_t* pixels,
    size_t width,
    size_t height,
    uint8_t* output) {
    CompressEtc2Rgba(
        pixels,
        reinterpret_cast<uint64_t*>(output),
        static_cast<uint32_t>((width / 4) * (height / 4)),
        width,
        true);
}
