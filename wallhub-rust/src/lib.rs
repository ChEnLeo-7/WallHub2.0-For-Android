//! WallHub Rust core for the kSteam + Rust hybrid architecture.
//!
//! Phase 1 scope (migration plan `docs/ksteam-rust-hybrid-migration-plan.md`, Phase 2
//! Week 1-2): the CPU-bound depot primitives — Adler-32 verification, LZ4/ZSTD
//! decompression and Steam depot chunk crypto. Network chunk download and UniFFI Kotlin
//! bindings are later phases and intentionally absent so this crate stays host-testable
//! without the Android NDK.

pub mod compression;
pub mod crypto;
pub mod depot;

/// Semantic version of the Rust engine, surfaced to the Kotlin side once bindings land.
pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Human-readable engine banner for diagnostics.
pub fn engine_banner() -> String {
    format!("wallhub-rust {} (depot core)", ENGINE_VERSION)
}
