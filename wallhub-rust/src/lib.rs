//! WallHub Rust core for the kSteam + Rust hybrid architecture.
//!
//! CPU-bound depot primitives: Adler-32 verification, LZ4/ZSTD
//! decompression and Steam depot chunk crypto. Network chunk download and UniFFI Kotlin
//! bindings are later phases and intentionally absent so this crate stays host-testable
//! without the Android NDK.

pub mod compression;
pub mod crypto;
pub mod depot;
pub mod ffi;
pub mod net;

/// Semantic version of the Rust engine, surfaced to the Kotlin side once bindings land.
pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Human-readable engine banner for diagnostics.
pub fn engine_banner() -> String {
    format!("wallhub-rust {} (depot core)", ENGINE_VERSION)
}
