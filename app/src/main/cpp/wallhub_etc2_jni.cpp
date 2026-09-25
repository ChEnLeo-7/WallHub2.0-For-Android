// JNI boundary for `com.wallhub.prototype.mpkg.Etc2Compressor`.
//
// Two things happen here and nowhere else: the Java byte array is copied into a
// buffer whose lifetime and alignment this translation unit controls, and the
// encoded result is copied back into a Java byte array. Everything else lives in
// `wallhub_etc2.cpp`, which is also what the host parity harness compiles.

#include <jni.h>

#include <cstdint>
#include <vector>

#include "wallhub_etc2.h"

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wallhub_prototype_mpkg_Etc2Compressor_nativeCompressRgba(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray pixels,
    jint width,
    jint height) {
    if (pixels == nullptr || width <= 0 || height <= 0) {
        return nullptr;
    }
    const jsize input_length = env->GetArrayLength(pixels);
    const int64_t pixel_count = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    const int64_t expected_bytes = pixel_count * 4;
    if (expected_bytes != static_cast<int64_t>(input_length)) {
        return nullptr;
    }

    std::vector<uint8_t> rgba(static_cast<size_t>(input_length));
    env->GetByteArrayRegion(
        pixels,
        0,
        input_length,
        reinterpret_cast<jbyte*>(rgba.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        return nullptr;
    }

    std::vector<uint8_t> encoded(static_cast<size_t>(pixel_count));
    wallhub_etc2_compress_rgba(
        rgba.data(),
        static_cast<size_t>(width),
        static_cast<size_t>(height),
        encoded.data());

    jbyteArray result = env->NewByteArray(static_cast<jsize>(encoded.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(encoded.size()),
        reinterpret_cast<const jbyte*>(encoded.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        return nullptr;
    }
    return result;
}
