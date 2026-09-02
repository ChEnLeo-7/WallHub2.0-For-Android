//! Full Steam depot chunk decoding: IV/payload crypto, compression detection, checksum.
//!
//! Mirrors the JavaSteam `DepotChunk.process` contract so the Kotlin and Rust engines stay
//! byte-compatible. ZStandard payloads are fully supported; the legacy VZip (LZMA) and
//! PKZip containers are rejected with [`DepotChunkError::UnsupportedCompression`] until
//! their decoders are ported (the Kotlin engine currently covers them).

use crate::compression::ChunkCompression;
use crate::crypto::aes;
use crate::depot::verify;

/// Failure modes of [`decrypt_depot_chunk`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DepotChunkError {
    InvalidInput(String),
    UnsupportedCompression(ChunkCompression),
    ChecksumMismatch { expected: u32, actual: u32 },
    LengthMismatch { expected: usize, actual: usize },
}

impl std::fmt::Display for DepotChunkError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DepotChunkError::InvalidInput(message) => {
                write!(formatter, "invalid depot chunk: {message}")
            }
            DepotChunkError::UnsupportedCompression(compression) => {
                write!(
                    formatter,
                    "unsupported depot chunk compression: {compression:?}"
                )
            }
            DepotChunkError::ChecksumMismatch { expected, actual } => {
                write!(
                    formatter,
                    "depot chunk checksum mismatch: expected {expected:#010x}, got {actual:#010x}"
                )
            }
            DepotChunkError::LengthMismatch { expected, actual } => {
                write!(
                    formatter,
                    "depot chunk decompressed to {actual} bytes, expected {expected}"
                )
            }
        }
    }
}

impl std::error::Error for DepotChunkError {}

/// Decrypts, decompresses and verifies one encrypted Steam depot chunk.
///
/// * `encrypted` — raw CDN payload (ECB-encrypted IV followed by AES-CBC ciphertext).
/// * `depot_key` — the 32-byte depot decryption key.
/// * `expected_checksum` — the manifest Adler-32 checksum of the uncompressed chunk.
/// * `uncompressed_length` — the manifest uncompressed chunk size.
pub fn decrypt_depot_chunk(
    encrypted: &[u8],
    depot_key: &[u8; 32],
    expected_checksum: u32,
    uncompressed_length: usize,
) -> Result<Vec<u8>, DepotChunkError> {
    if encrypted.len() <= 16 {
        return Err(DepotChunkError::InvalidInput(format!(
            "encrypted payload must exceed the 16-byte IV, got {} bytes",
            encrypted.len()
        )));
    }
    let iv = aes::decrypt_chunk_iv(depot_key, encrypted).map_err(DepotChunkError::InvalidInput)?;
    let decrypted = aes::decrypt_chunk_payload(depot_key, &iv, &encrypted[16..])
        .map_err(DepotChunkError::InvalidInput)?;

    let compression = ChunkCompression::detect(&decrypted).ok_or_else(|| {
        DepotChunkError::InvalidInput("unrecognized compression magic".to_string())
    })?;
    let decompressed = match compression {
        ChunkCompression::Zstd => crate::compression::zstd::decompress_vzstd_container(&decrypted)
            .map_err(DepotChunkError::InvalidInput)?,
        other => return Err(DepotChunkError::UnsupportedCompression(other)),
    };

    if decompressed.len() != uncompressed_length {
        return Err(DepotChunkError::LengthMismatch {
            expected: uncompressed_length,
            actual: decompressed.len(),
        });
    }
    let actual = verify::steam_adler32(&decompressed);
    if actual != expected_checksum {
        return Err(DepotChunkError::ChecksumMismatch {
            expected: expected_checksum,
            actual,
        });
    }
    Ok(decompressed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ::aes::cipher::generic_array::GenericArray;
    use ::aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
    use ::aes::Aes256;

    fn encrypt_like_steam(key: &[u8; 32], payload: &[u8]) -> Vec<u8> {
        // Build the VZstd container exactly like Steam does: "VSZa" + header CRC + zstd
        // frame + footer (CRC + uncompressed size + padding + "zsv").
        let frame = zstd::stream::encode_all(payload, 3).expect("zstd encode for fixture");
        let mut container = vec![b'V', b'S', b'Z', b'a'];
        container.extend_from_slice(&[0u8; 4]);
        container.extend_from_slice(&frame);
        container.extend_from_slice(&[0u8; 4]);
        container.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        container.extend_from_slice(&[0u8; 4]);
        container.extend_from_slice(b"zsv");

        let cipher = Aes256::new(GenericArray::from_slice(key));
        // Random-but-deterministic IV.
        let iv = [0x5Au8; 16];
        let mut ecb_iv = iv;
        let mut block = GenericArray::clone_from_slice(&ecb_iv);
        cipher.encrypt_block(&mut block);
        ecb_iv.copy_from_slice(block.as_slice());

        let padding = 16 - (container.len() % 16);
        let mut padded = container;
        padded.extend(std::iter::repeat_n(padding as u8, padding));
        let mut previous = iv;
        let mut ciphertext = Vec::with_capacity(padded.len());
        for chunk in padded.as_chunks::<16>().0 {
            let mut array = GenericArray::clone_from_slice(chunk);
            for (byte, previous_byte) in array.iter_mut().zip(previous.iter()) {
                *byte ^= previous_byte;
            }
            cipher.encrypt_block(&mut array);
            previous = array.into();
            ciphertext.extend_from_slice(array.as_slice());
        }

        let mut encrypted = ecb_iv.to_vec();
        encrypted.extend_from_slice(&ciphertext);
        encrypted
    }

    #[test]
    fn roundtrips_a_modern_zstd_chunk() {
        let key = [0x11u8; 32];
        let payload: Vec<u8> = (0..64 * 1024usize)
            .map(|index| (index * 13 % 256) as u8)
            .collect();
        let checksum = verify::steam_adler32(&payload);
        let encrypted = encrypt_like_steam(&key, &payload);

        let restored = decrypt_depot_chunk(&encrypted, &key, checksum, payload.len())
            .expect("chunk roundtrip");
        assert_eq!(restored, payload);
    }

    #[test]
    fn rejects_a_wrong_depot_key() {
        let key = [0x11u8; 32];
        let payload = vec![9u8; 2048];
        let checksum = verify::steam_adler32(&payload);
        let encrypted = encrypt_like_steam(&key, &payload);

        assert!(matches!(
            decrypt_depot_chunk(&encrypted, &[0x22u8; 32], checksum, payload.len()),
            Err(DepotChunkError::InvalidInput(_))
        ));
    }

    #[test]
    fn rejects_a_corrupted_payload_with_checksum_error() {
        let key = [0x33u8; 32];
        let payload = vec![3u8; 4096];
        let encrypted = encrypt_like_steam(&key, &payload);
        // Flip a checksum expectation: decoding still succeeds, verification must fail.
        assert!(matches!(
            decrypt_depot_chunk(
                &encrypted,
                &key,
                verify::steam_adler32(&payload) + 1,
                payload.len()
            ),
            Err(DepotChunkError::ChecksumMismatch { .. })
        ));
    }

    #[test]
    fn rejects_undersized_payloads() {
        let key = [0x44u8; 32];
        assert!(matches!(
            decrypt_depot_chunk(&[0u8; 8], &key, 1, 16),
            Err(DepotChunkError::InvalidInput(_))
        ));
    }

    #[test]
    fn decrypt_helpers_roundtrip() {
        let cipher = Aes256::new(GenericArray::from_slice(&[0u8; 32]));
        let mut block = GenericArray::clone_from_slice(&[1u8; 16]);
        cipher.encrypt_block(&mut block);
        cipher.decrypt_block(&mut block);
        assert_eq!(block.as_slice(), &[1u8; 16]);
    }
}
