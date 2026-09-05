//! Steam VZip (`VZa`) container decoding.

use std::io::Cursor;

use lzma_rs::decompress::{Options, UnpackedSize};

const HEADER_LENGTH: usize = 7;
const LZMA_PROPERTIES_LENGTH: usize = 5;
const FOOTER_LENGTH: usize = 10;
pub(crate) const MAX_DEPOT_CHUNK_BYTES: usize = 64 * 1024 * 1024;
const MAX_VZIP_DICTIONARY_BYTES: usize = 8 * 1024 * 1024;

/// Decompresses one decrypted Steam VZip container into its manifest-declared size.
pub fn decompress_vzip_container(
    container: &[u8],
    expected_uncompressed_size: usize,
) -> Result<Vec<u8>, String> {
    if expected_uncompressed_size == 0 || expected_uncompressed_size > MAX_DEPOT_CHUNK_BYTES {
        return Err(format!(
            "VZip output size {expected_uncompressed_size} is outside 1..={MAX_DEPOT_CHUNK_BYTES}"
        ));
    }
    if container.len() < HEADER_LENGTH + LZMA_PROPERTIES_LENGTH + FOOTER_LENGTH {
        return Err(format!(
            "VZip container is too short: {} bytes",
            container.len()
        ));
    }
    if &container[..3] != b"VZa" {
        return Err("VZip header must be VZa".to_string());
    }
    if &container[container.len() - 2..] != b"zv" {
        return Err("VZip footer must be zv".to_string());
    }

    let footer_size_offset = container.len() - 6;
    let footer_size = i32::from_le_bytes(
        container[footer_size_offset..footer_size_offset + 4]
            .try_into()
            .expect("validated VZip footer size"),
    );
    if footer_size <= 0 || footer_size as usize != expected_uncompressed_size {
        return Err(format!(
            "VZip footer size {footer_size} does not match expected {expected_uncompressed_size}"
        ));
    }

    let dictionary_size = u32::from_le_bytes(
        container[8..12]
            .try_into()
            .expect("validated VZip LZMA properties"),
    ) as usize;
    if dictionary_size > MAX_VZIP_DICTIONARY_BYTES {
        return Err(format!(
            "VZip dictionary size {dictionary_size} exceeds {MAX_VZIP_DICTIONARY_BYTES}"
        ));
    }

    let lzma_end = container.len() - FOOTER_LENGTH;
    let mut input = Cursor::new(&container[HEADER_LENGTH..lzma_end]);
    let options = Options {
        unpacked_size: UnpackedSize::UseProvided(Some(expected_uncompressed_size as u64)),
        memlimit: Some(MAX_VZIP_DICTIONARY_BYTES),
        allow_incomplete: false,
    };
    let mut output = vec![0u8; expected_uncompressed_size];
    let written = {
        let mut destination = Cursor::new(output.as_mut_slice());
        lzma_rs::lzma_decompress_with_options(&mut input, &mut destination, &options)
            .map_err(|error| format!("VZip/LZMA decode failed: {error}"))?;
        destination.position() as usize
    };
    if written != expected_uncompressed_size {
        return Err(format!(
            "VZip decompressed to {written} bytes, expected {expected_uncompressed_size}"
        ));
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_invalid_vzip_boundaries_before_decoding() {
        assert!(decompress_vzip_container(b"VZa", 1).is_err());

        let mut container = vec![0u8; HEADER_LENGTH + LZMA_PROPERTIES_LENGTH + FOOTER_LENGTH];
        container[..3].copy_from_slice(b"VZa");
        let end = container.len();
        container[end - 6..end - 2].copy_from_slice(&1i32.to_le_bytes());
        assert!(decompress_vzip_container(&container, 1).is_err());

        container[end - 2..].copy_from_slice(b"zv");
        assert!(decompress_vzip_container(&container, 2).is_err());
    }

    #[test]
    fn rejects_unbounded_vzip_allocations() {
        assert!(decompress_vzip_container(&[], 0).is_err());
        assert!(decompress_vzip_container(&[], MAX_DEPOT_CHUNK_BYTES + 1).is_err());

        let mut container = vec![0u8; HEADER_LENGTH + LZMA_PROPERTIES_LENGTH + FOOTER_LENGTH];
        container[..3].copy_from_slice(b"VZa");
        container[8..12].copy_from_slice(&((MAX_VZIP_DICTIONARY_BYTES + 1) as u32).to_le_bytes());
        let end = container.len();
        container[end - 6..end - 2].copy_from_slice(&1i32.to_le_bytes());
        container[end - 2..].copy_from_slice(b"zv");
        assert!(decompress_vzip_container(&container, 1).is_err());
    }
}
