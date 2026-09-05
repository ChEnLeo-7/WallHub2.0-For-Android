//! Android JNI exports bridging the Rust depot core into WallHub.
//!
//! The Kotlin peer is `com.wallhub.android.data.downloads.WallHubRust`; all functions are
//! synchronous and do their own async scheduling internally so the Kotlin side can simply
//! dispatch them onto IO dispatcher threads.

use std::sync::OnceLock;

use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use jni::JNIEnv;

use crate::depot::chunk;
use crate::depot::verify;

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("failed to build tokio runtime")
    })
}

fn read_bytes(env: &mut JNIEnv, array: &JByteArray) -> Result<Vec<u8>, String> {
    let length = env
        .get_array_length(array)
        .map_err(|error| error.to_string())? as usize;
    let mut buffer = vec![0i8; length];
    env.get_byte_array_region(array, 0, &mut buffer)
        .map_err(|error| error.to_string())?;
    Ok(buffer.into_iter().map(|byte| byte as u8).collect())
}

fn write_bytes(env: &mut JNIEnv, bytes: &[u8]) -> Result<jbyteArray, String> {
    let output = env
        .new_byte_array(bytes.len() as i32)
        .map_err(|error| error.to_string())?;
    let signed: Vec<i8> = bytes.iter().map(|byte| *byte as i8).collect();
    env.set_byte_array_region(&output, 0, &signed)
        .map_err(|error| error.to_string())?;
    Ok(output.into_raw())
}

fn read_depot_key(env: &mut JNIEnv, value: &JByteArray) -> Result<[u8; 32], String> {
    let bytes = read_bytes(env, value)?;
    if bytes.len() != 32 {
        return Err(format!("depot key must be 32 bytes, got {}", bytes.len()));
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Ok(key)
}

fn read_uncompressed_length(value: jint) -> Result<usize, String> {
    let length = usize::try_from(value).map_err(|_| format!("invalid chunk length {value}"))?;
    if length == 0 || length > crate::compression::vzip::MAX_DEPOT_CHUNK_BYTES {
        return Err(format!("invalid chunk length {value}"));
    }
    Ok(length)
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    let java_str = env.get_string(value).map_err(|error| error.to_string())?;
    Ok(java_str.to_string_lossy().into_owned())
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

/// Runs `body`, converting panics and errors into Java runtime exceptions.
fn guarded<T, F>(env: &mut JNIEnv, fallback: T, body: F) -> T
where
    F: FnOnce(&mut JNIEnv) -> Result<T, String>,
{
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| body(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            throw(env, &message);
            fallback
        }
        Err(_) => {
            throw(env, "wallhub-rust engine panicked");
            fallback
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wallhub_android_data_downloads_WallHubRust_engineVersion<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let version = env
            .new_string(crate::engine_banner())
            .map_err(|error| error.to_string())?;
        Ok(version.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wallhub_android_data_downloads_WallHubRust_verifyChunk<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    data: JByteArray<'local>,
    expected_checksum: jint,
) -> jboolean {
    let mut result: jboolean = 0;
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| read_bytes(&mut env, &data))) {
        Ok(Ok(bytes)) => {
            result = u8::from(verify::steam_adler32(&bytes) == expected_checksum as u32);
        }
        Ok(Err(message)) => throw(&mut env, &message),
        Err(_) => throw(&mut env, "wallhub-rust engine panicked"),
    }
    result
}

#[no_mangle]
pub extern "system" fn Java_com_wallhub_android_data_downloads_WallHubRust_decodeChunk<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    encrypted: JByteArray<'local>,
    depot_key: JByteArray<'local>,
    expected_checksum: jint,
    uncompressed_length: jint,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let encrypted_bytes = read_bytes(env, &encrypted)?;
        let key = read_depot_key(env, &depot_key)?;
        let expected_length = read_uncompressed_length(uncompressed_length)?;
        let decoded = chunk::decrypt_depot_chunk(
            &encrypted_bytes,
            &key,
            expected_checksum as u32,
            expected_length,
        )
        .map_err(|error| error.to_string())?;
        write_bytes(env, &decoded)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wallhub_android_data_downloads_WallHubRust_downloadChunk<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    timeout_ms: jint,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let target = read_string(env, &url)?;
        let bytes = runtime().block_on(crate::net::download_resource(
            &target,
            timeout_ms.max(1_000) as u64,
        ))?;
        write_bytes(env, &bytes)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wallhub_android_data_downloads_WallHubRust_downloadAndDecodeChunk<
    'local,
>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    url: JString<'local>,
    depot_key: JByteArray<'local>,
    expected_checksum: jint,
    uncompressed_length: jint,
    timeout_ms: jint,
) -> jbyteArray {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let target = read_string(env, &url)?;
        let key = read_depot_key(env, &depot_key)?;
        let expected_length = read_uncompressed_length(uncompressed_length)?;
        let encrypted = runtime().block_on(crate::net::download_resource(
            &target,
            timeout_ms.max(1_000) as u64,
        ))?;
        let decoded =
            chunk::decrypt_depot_chunk(&encrypted, &key, expected_checksum as u32, expected_length)
                .map_err(|error| error.to_string())?;
        write_bytes(env, &decoded)
    })
}
