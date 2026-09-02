//! AES-256 ECB/CBC decryption for Steam depot chunks.
//!
//! The transport format (matching JavaSteam `DepotChunk.process`) is: the first 16 bytes of
//! the encrypted chunk are the CBC IV encrypted with AES-256-ECB under the depot key; the
//! remainder is AES-256-CBC with PKCS#7 padding.

use aes::cipher::generic_array::GenericArray;
use aes::cipher::{BlockDecrypt, KeyInit};
use aes::Aes256;

/// AES-256-ECB decrypts exactly one 16-byte block in place.
pub fn decrypt_ecb_block(key: &[u8; 32], block: &mut [u8; 16]) {
    let cipher = Aes256::new(GenericArray::from_slice(key));
    let mut array = GenericArray::clone_from_slice(block);
    cipher.decrypt_block(&mut array);
    block.copy_from_slice(array.as_slice());
}

/// AES-256-CBC decrypts `ciphertext` (a non-zero multiple of 16 bytes) with `key`/`iv`.
fn decrypt_cbc(key: &[u8; 32], iv: &[u8; 16], ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    if ciphertext.is_empty() || !ciphertext.len().is_multiple_of(16) {
        return Err(format!(
            "AES-CBC payload must be a non-zero multiple of 16 bytes, got {}",
            ciphertext.len()
        ));
    }
    let cipher = Aes256::new(GenericArray::from_slice(key));
    let mut plaintext = Vec::with_capacity(ciphertext.len());
    let mut previous = *iv;
    for block in ciphertext.as_chunks::<16>().0 {
        let mut array = GenericArray::clone_from_slice(block);
        let encrypted_block = array;
        cipher.decrypt_block(&mut array);
        for (byte, previous_byte) in array.iter_mut().zip(previous.iter()) {
            *byte ^= previous_byte;
        }
        plaintext.extend_from_slice(array.as_slice());
        previous = encrypted_block.into();
    }
    Ok(plaintext)
}

/// Removes and validates PKCS#7 padding, returning the unpadded length.
pub fn pkcs7_unpadded_len(padded: &[u8]) -> Result<usize, String> {
    let last = *padded
        .last()
        .ok_or_else(|| "cannot unpad an empty payload".to_string())?;
    let padding = last as usize;
    if padding == 0 || padding > 16 || padding > padded.len() {
        return Err(format!("invalid PKCS#7 padding byte {padding}"));
    }
    if padded[padded.len() - padding..]
        .iter()
        .any(|byte| *byte != last)
    {
        return Err("inconsistent PKCS#7 padding bytes".to_string());
    }
    Ok(padded.len() - padding)
}

/// Decrypts the Steam depot chunk IV: the first 16 encrypted bytes under AES-256-ECB.
pub fn decrypt_chunk_iv(key: &[u8; 32], encrypted: &[u8]) -> Result<[u8; 16], String> {
    if encrypted.len() < 16 {
        return Err(format!(
            "encrypted depot chunk shorter than the IV: {} bytes",
            encrypted.len()
        ));
    }
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&encrypted[..16]);
    decrypt_ecb_block(key, &mut iv);
    Ok(iv)
}

/// AES-256-CBC decrypts the payload after the IV and removes PKCS#7 padding.
pub fn decrypt_chunk_payload(
    key: &[u8; 32],
    iv: &[u8; 16],
    encrypted: &[u8],
) -> Result<Vec<u8>, String> {
    let mut padded = decrypt_cbc(key, iv, encrypted)?;
    let unpadded_len = pkcs7_unpadded_len(&padded)?;
    padded.truncate(unpadded_len);
    Ok(padded)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Encrypts with CBC+PKCS#7 so the decrypt path can be round-tripped.
    fn cbc_pkcs7_encrypt(key: &[u8; 32], iv: &[u8; 16], plaintext: &[u8]) -> Vec<u8> {
        use aes::cipher::{generic_array::GenericArray, BlockEncrypt};
        let cipher = Aes256::new(GenericArray::from_slice(key));
        let padding = 16 - (plaintext.len() % 16);
        let mut padded = plaintext.to_vec();
        padded.extend(std::iter::repeat_n(padding as u8, padding));
        let mut previous = *iv;
        let mut output = Vec::with_capacity(padded.len());
        for block in padded.as_chunks::<16>().0 {
            let mut array = GenericArray::clone_from_slice(block);
            for (byte, previous_byte) in array.iter_mut().zip(previous.iter()) {
                *byte ^= previous_byte;
            }
            cipher.encrypt_block(&mut array);
            previous = array.into();
            output.extend_from_slice(array.as_slice());
        }
        output
    }

    #[test]
    fn roundtrips_cbc_payloads_across_block_boundaries() {
        let key = [0x42u8; 32];
        let iv = [0x17u8; 16];
        for size in [1usize, 15, 16, 17, 63, 64, 4096] {
            let plaintext: Vec<u8> = (0..size).map(|index| (index * 7 % 256) as u8).collect();
            let ciphertext = cbc_pkcs7_encrypt(&key, &iv, &plaintext);
            let restored = decrypt_chunk_payload(&key, &iv, &ciphertext).expect("cbc roundtrip");
            assert_eq!(restored, plaintext, "roundtrip failed at size {size}");
        }
    }

    #[test]
    fn rejects_corrupted_padding() {
        let key = [0x42u8; 32];
        let iv = [0x17u8; 16];
        let ciphertext = cbc_pkcs7_encrypt(&key, &iv, b"exactly 16 byte");
        let mut corrupted = ciphertext;
        let last = corrupted.len() - 1;
        corrupted[last] ^= 0xFF;
        assert!(decrypt_chunk_payload(&key, &iv, &corrupted).is_err());
    }

    #[test]
    fn rejects_short_payloads() {
        let key = [0x42u8; 32];
        assert!(decrypt_chunk_iv(&key, &[0u8; 8]).is_err());
        assert!(decrypt_cbc(&key, &[0u8; 16], &[0u8; 15]).is_err());
    }
}
