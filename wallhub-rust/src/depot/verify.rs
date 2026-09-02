//! Adler-32 checksum verification for depot payloads (RFC 1950).

use adler2::Adler32;

/// Computes the Adler-32 checksum of a payload.
pub fn adler32(data: &[u8]) -> u32 {
    let mut hasher = Adler32::new();
    hasher.write_slice(data);
    hasher.checksum()
}

/// Returns true when the payload matches the manifest's expected checksum.
pub fn verify_chunk(data: &[u8], expected_checksum: u32) -> bool {
    adler32(data) == expected_checksum
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_reference_vectors() {
        assert_eq!(adler32(b""), 0x0000_0001);
        // Standard test vector from RFC 1950 usage: Adler-32("123456789") = 0x091E01DE.
        assert_eq!(adler32(b"123456789"), 0x091E_01DE);
    }

    #[test]
    fn verifies_and_rejects_payloads() {
        let payload = b"wallhub depot chunk payload";
        let checksum = adler32(payload);
        assert!(verify_chunk(payload, checksum));
        assert!(!verify_chunk(payload, checksum ^ 0x1));
        assert!(!verify_chunk(&payload[..payload.len() - 1], checksum));
    }
}
