//! Raw LZ4 block decompression via the pure-Rust `lz4_flex` backend.

/// Decompresses a raw LZ4 block into a freshly allocated buffer of `uncompressed_size` bytes.
pub fn decompress_lz4(compressed: &[u8], uncompressed_size: usize) -> Result<Vec<u8>, String> {
    lz4_flex::decompress(compressed, uncompressed_size).map_err(|error| error.to_string())
}

/// Decompresses a raw LZ4 block into a caller-provided buffer, returning the written size.
pub fn decompress_lz4_into(compressed: &[u8], destination: &mut [u8]) -> Result<usize, String> {
    lz4_flex::decompress_into(compressed, destination).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrips_arbitrary_payloads() {
        for size in [0usize, 1, 64, 4096, 1 << 20] {
            let payload: Vec<u8> = (0..size).map(|index| (index % 251) as u8).collect();
            let compressed = lz4_flex::compress(&payload);
            let restored = decompress_lz4(&compressed, size).expect("lz4 roundtrip");
            assert_eq!(restored, payload, "roundtrip failed at size {size}");
        }
    }

    #[test]
    fn rejects_truncated_blocks() {
        let payload = vec![7u8; 512];
        let compressed = lz4_flex::compress(&payload);
        let error = decompress_lz4(&compressed[..compressed.len() / 2], 512);
        assert!(error.is_err());
    }
}
