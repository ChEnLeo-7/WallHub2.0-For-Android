//! Checksum verification for depot payloads.
//!
//! Steam manifest chunk checksums follow the JavaSteam/SteamKit convention: the s1
//! accumulator starts at **0** (not the RFC 1950 initial value of 1). `steam_adler32` is
//! the manifest-compatible variant used for chunk verification; `adler32` is the plain
//! RFC reference implementation kept for vectors and comparison.

use adler2::Adler32;

/// Computes the RFC 1950 Adler-32 checksum of a payload.
pub fn adler32(data: &[u8]) -> u32 {
    let mut hasher = Adler32::new();
    hasher.write_slice(data);
    hasher.checksum()
}

/// Computes the Steam/JavaSteam manifest checksum (s1 seeded with 0).
pub fn steam_adler32(data: &[u8]) -> u32 {
    const BASE: u32 = 65_521;
    let mut s1: u32 = 0;
    let mut s2: u32 = 0;
    for &byte in data {
        s1 = (s1 + byte as u32) % BASE;
        s2 = (s2 + s1) % BASE;
    }
    (s2 << 16) | s1
}

/// Returns true when the payload matches the manifest's expected checksum.
pub fn verify_chunk(data: &[u8], expected_checksum: u32) -> bool {
    steam_adler32(data) == expected_checksum
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rfc_implementation_matches_reference_vectors() {
        assert_eq!(adler32(b""), 0x0000_0001);
        // Standard test vector from RFC 1950 usage: Adler-32("123456789") = 0x091E01DE.
        assert_eq!(adler32(b"123456789"), 0x091E_01DE);
    }

    #[test]
    fn steam_variant_seeds_s1_with_zero() {
        // Empty payload: JavaSteam's Adler32.calculate returns 0, RFC returns 1.
        assert_eq!(steam_adler32(b""), 0x0000_0000);
        // s1 is one less than RFC; s2 loses one accumulation step per byte.
        let rfc = adler32(b"123456789");
        let steam = steam_adler32(b"123456789");
        assert_eq!(steam & 0xFFFF, (rfc & 0xFFFF) - 1);
        assert_eq!(steam >> 16, (rfc >> 16) - 9);
    }

    #[test]
    fn verifies_and_rejects_payloads() {
        let payload = b"wallhub depot chunk payload";
        let checksum = steam_adler32(payload);
        assert!(verify_chunk(payload, checksum));
        assert!(!verify_chunk(payload, checksum ^ 0x1));
        let mut corrupted = *payload;
        corrupted[0] = corrupted[0].wrapping_add(1);
        assert!(!verify_chunk(&corrupted, checksum));
    }
}
