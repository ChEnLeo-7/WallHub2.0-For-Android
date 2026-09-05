//! Full Steam depot chunk decoding: IV/payload crypto, compression detection, checksum.
//!
//! Mirrors the JavaSteam `DepotChunk.process` contract so the Kotlin and Rust engines stay
//! byte-compatible. ZStandard and legacy VZip/LZMA payloads are supported; PKZip remains
//! rejected with [`DepotChunkError::UnsupportedCompression`].

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
    if uncompressed_length == 0
        || uncompressed_length > crate::compression::vzip::MAX_DEPOT_CHUNK_BYTES
    {
        return Err(DepotChunkError::InvalidInput(format!(
            "uncompressed length {uncompressed_length} is outside 1..={}",
            crate::compression::vzip::MAX_DEPOT_CHUNK_BYTES
        )));
    }
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
        ChunkCompression::VzipLzma => {
            crate::compression::vzip::decompress_vzip_container(&decrypted, uncompressed_length)
                .map_err(DepotChunkError::InvalidInput)?
        }
        ChunkCompression::Pkzip => {
            return Err(DepotChunkError::UnsupportedCompression(
                ChunkCompression::Pkzip,
            ))
        }
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
    use sha1::{Digest, Sha1};

    const STEAMKIT_VZIP_FIXTURE_HEX: &str =
        "bdd0c894b719592591164f592ea669d690cfadd49012c58ea0c58d82a2f50ae763acf2c4df2abbac2c870d4e3e99106ecf54734fd8a2aab6cb9d090cf9a006dad8deaa586c88b86fd3602c9234dfb185708a452f975bcb86a3f983238ff09209543dc93c1f15fa7f931679d383bbd952a922e38014f91323f220d5ecd4fe83063c8e46bd379817f12e1cb2177791bd9313620f4b1d8d29a79a2a7dafdd4349d39ed3993194d6867447c0b1f25bd5a18dd9115e4e6da0294f272b91dba5ab5ab6681ce47375f428d6bb5510683755e331739267d8612ae53146f61d9dc68b2f19f5093e3724e419915ebe39ca743fa15989f9b7b52f5f59199955759c3bf97350627738201ab58077f2deaa0f299420cf5c517cfdca07c641f1beb509dc8b2d0ff91d81fe220943ceb91ba2315a575024";
    const STEAMKIT_VZIP_KEY: [u8; 32] = [
        0xE5, 0xF6, 0xAE, 0xD5, 0x5E, 0x9E, 0xCE, 0x42, 0x9E, 0x56, 0xB8, 0x13, 0xFB, 0xF6, 0xBF,
        0xE9, 0x24, 0xF3, 0xCF, 0x72, 0x97, 0x2F, 0xDB, 0xD0, 0x57, 0x1F, 0xFC, 0xAD, 0x9F, 0x2F,
        0x7D, 0xAA,
    ];

    fn hex(value: &str) -> Vec<u8> {
        let bytes = value.as_bytes();
        let mut output = Vec::new();
        let mut index = 0;
        while index < bytes.len() {
            output.push((hex_digit(bytes[index]) << 4) | hex_digit(bytes[index + 1]));
            index += 2;
        }
        output
    }

    fn hex_digit(value: u8) -> u8 {
        match value {
            b'0'..=b'9' => value - b'0',
            b'a'..=b'f' => value - b'a' + 10,
            _ => panic!("invalid fixture hex"),
        }
    }

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
    fn decodes_the_pinned_steamkit_vzip_fixture() {
        // SteamRE/SteamKit commit 2e9b82a, DepotChunkFacts VZip fixture.
        // Fixture SHA-256: cb83f1311203209511d3b32070603ce1cde373fd35ca49c217ad25858f8f7ae7.
        let restored = decrypt_depot_chunk(
            &hex(STEAMKIT_VZIP_FIXTURE_HEX),
            &STEAMKIT_VZIP_KEY,
            2_894_626_744,
            798,
        )
        .expect("SteamKit VZip fixture");

        assert_eq!(restored.len(), 798);
        assert_eq!(
            format!("{:X}", Sha1::digest(&restored)),
            "7B8567D9B3C09295CDBF4978C32B348D8E76C750"
        );
    }

    #[test]
    fn rejects_the_vzip_fixture_with_a_wrong_manifest_checksum() {
        assert!(matches!(
            decrypt_depot_chunk(
                &hex(STEAMKIT_VZIP_FIXTURE_HEX),
                &STEAMKIT_VZIP_KEY,
                2_894_626_745,
                798,
            ),
            Err(DepotChunkError::ChecksumMismatch { .. })
        ));
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
