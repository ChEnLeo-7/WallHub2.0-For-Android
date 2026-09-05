//! Engine-neutral compression primitives used by depot chunk decoding.

pub mod lz4;
pub mod vzip;
pub mod zstd;

/// Compression container observed inside a decrypted Steam depot chunk.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChunkCompression {
    /// `VSZa` magic — ZStandard (the format modern Steam depots use).
    Zstd,
    /// `VZa` magic — legacy VZip (LZMA) container.
    VzipLzma,
    /// `PK\x03\x04` magic — PKZip (deflate) container.
    Pkzip,
}

impl ChunkCompression {
    /// Detects the container from the decrypted payload magic bytes.
    pub fn detect(decrypted: &[u8]) -> Option<ChunkCompression> {
        if decrypted.len() < 4 {
            return None;
        }
        if decrypted[0..4] == *b"VSZa" {
            return Some(ChunkCompression::Zstd);
        }
        if decrypted[0..3] == *b"VZa" {
            return Some(ChunkCompression::VzipLzma);
        }
        if decrypted[0..4] == [b'P', b'K', 0x03, 0x04] {
            return Some(ChunkCompression::Pkzip);
        }
        None
    }
}
