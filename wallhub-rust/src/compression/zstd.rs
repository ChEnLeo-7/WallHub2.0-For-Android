//! ZStandard decompression via the pure-Rust `ruzstd` decoder.
//!
//! Only decoding is required by the depot pipeline: Steam always hands us already
//! compressed chunks, and the Kotlin engine keeps producing compressed artifacts.

use std::io::Read;

/// Decompresses a complete ZStandard frame into `uncompressed_size` bytes.
pub fn decompress_zstd(compressed: &[u8], uncompressed_size: usize) -> Result<Vec<u8>, String> {
    let reader = ruzstd::StreamingDecoder::new(compressed).map_err(|error| error.to_string())?;
    let mut output = Vec::with_capacity(uncompressed_size);
    reader
        .take(uncompressed_size as u64)
        .read_to_end(&mut output)
        .map_err(|error| error.to_string())?;
    if output.len() != uncompressed_size {
        return Err(format!(
            "zstd frame decompressed to {} bytes, expected {uncompressed_size}",
            output.len()
        ));
    }
    Ok(output)
}

const VZSTD_HEADER_MAGIC: u32 = 0x615A_5356; // "VSZa" little-endian
const VZSTD_HEADER_SIZE: usize = 8;
const VZSTD_FOOTER_SIZE: usize = 15;

/// Decompresses a Steam `VSZa` (VZstd) container: an 8-byte header (magic + CRC), the raw
/// ZStandard frame, and a 15-byte footer whose second field holds the uncompressed size.
pub fn decompress_vzstd_container(buffer: &[u8]) -> Result<Vec<u8>, String> {
    if buffer.len() < VZSTD_HEADER_SIZE + VZSTD_FOOTER_SIZE {
        return Err(format!("VZstd container too small: {} bytes", buffer.len()));
    }
    let magic = u32::from_le_bytes(buffer[0..4].try_into().expect("4 bytes"));
    if magic != VZSTD_HEADER_MAGIC {
        return Err(format!("expecting VZstd header magic, got {magic:#010x}"));
    }
    if buffer[buffer.len() - 3..] != *b"zsv" {
        return Err("expecting VZstd footer magic".to_string());
    }
    let size_decrypted = u32::from_le_bytes(
        buffer[buffer.len() - VZSTD_FOOTER_SIZE + 4..buffer.len() - VZSTD_FOOTER_SIZE + 8]
            .try_into()
            .expect("4 bytes"),
    ) as usize;
    let frame = &buffer[VZSTD_HEADER_SIZE..buffer.len() - VZSTD_FOOTER_SIZE];
    decompress_zstd(frame, size_decrypted)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic payload large enough to exercise multiple zstd blocks.
    fn payload(size: usize) -> Vec<u8> {
        (0..size).map(|index| ((index * 31) % 255) as u8).collect()
    }

    #[test]
    fn roundtrips_a_standard_frame() {
        let original = payload(256 * 1024);
        let compressed = zstd::stream::encode_all(&original[..], 3).expect("zstd encode");
        let restored = decompress_zstd(&compressed, original.len()).expect("zstd decode");
        assert_eq!(restored, original);
    }

    #[test]
    fn rejects_garbage_frames() {
        assert!(decompress_zstd(&[0u8; 64], 1024).is_err());
    }

    #[test]
    fn roundtrips_a_vzstd_container() {
        use std::io::Write;
        let original = payload(64 * 1024);
        let frame = zstd::stream::encode_all(&original[..], 3).expect("zstd encode");
        let mut container = Vec::new();
        container.extend_from_slice(&VZSTD_HEADER_MAGIC.to_le_bytes());
        container.extend_from_slice(&[0u8; 4]); // header CRC (unchecked by the decoder)
        container.extend_from_slice(&frame);
        let mut footer = Vec::new();
        footer.extend_from_slice(&[0u8; 4]); // footer CRC (unchecked by the decoder)
        footer.extend_from_slice(&(original.len() as u32).to_le_bytes());
        footer.extend_from_slice(&[0u8; 4]);
        footer.extend_from_slice(b"zsv");
        let _ = std::io::sink().write_all(&footer);
        container.extend_from_slice(&footer);

        assert_eq!(
            decompress_vzstd_container(&container).expect("vzstd decode"),
            original
        );
    }

    #[test]
    fn rejects_broken_vzstd_containers() {
        assert!(decompress_vzstd_container(&[0u8; 32]).is_err());
        let mut bad_footer = vec![b'V', b'S', b'Z', b'a', 0, 0, 0, 0];
        bad_footer.extend_from_slice(&[0u8; 32]);
        assert!(decompress_vzstd_container(&bad_footer).is_err());
    }
}
