//! Network chunk download from Steam CDN hosts.

use std::time::Duration;

const DEFAULT_USER_AGENT: &str = concat!("WallHub-Rust/", env!("CARGO_PKG_VERSION"));

/// Downloads a complete Steam CDN resource (manifest, chunk, ...) and returns the raw bytes.
pub async fn download_resource(url: &str, timeout_ms: u64) -> Result<Vec<u8>, String> {
    let client = reqwest::Client::builder()
        .user_agent(DEFAULT_USER_AGENT)
        .timeout(Duration::from_millis(timeout_ms))
        .connect_timeout(Duration::from_secs(20))
        .build()
        .map_err(|error| format!("failed to build HTTP client: {error}"))?;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| error.to_string())?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("CDN request failed: HTTP {status}"));
    }
    let bytes = response.bytes().await.map_err(|error| error.to_string())?;
    Ok(bytes.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn surfaces_dns_failures_as_errors() {
        let result =
            download_resource("https://invalid.wallhub-rust.test/depot/0/chunk/00", 5_000).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn rejects_malformed_urls() {
        assert!(download_resource("not-a-url", 1_000).await.is_err());
    }
}
