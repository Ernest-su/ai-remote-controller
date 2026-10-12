use anyhow::{bail, Context, Result};
use tokio::sync::Semaphore;

static CAPTURE: Semaphore = Semaphore::const_new(1);
fn dimensions(png: &[u8]) -> Result<(u32, u32)> {
    if png.len() < 33
        || &png[..8] != b"\x89PNG\r\n\x1a\n"
        || png[8..12] != [0, 0, 0, 13]
        || &png[12..16] != b"IHDR"
    {
        bail!("screencap did not return a PNG image");
    }
    let width = u32::from_be_bytes(png[16..20].try_into()?);
    let height = u32::from_be_bytes(png[20..24].try_into()?);
    if width == 0
        || height == 0
        || width > 16384
        || height > 16384
        || u64::from(width) * u64::from(height) > 32 * 1024 * 1024
    {
        bail!("invalid screenshot dimensions");
    }
    Ok((width, height))
}

pub(crate) fn reserve() -> Result<tokio::sync::SemaphorePermit<'static>> {
    CAPTURE
        .try_acquire()
        .context("screen capture already in progress")
}
pub(crate) async fn capture() -> Result<(Vec<u8>, u32, u32)> {
    let _permit = reserve()?;
    let png =
        crate::tools::run_binary_command("screencap", &["-p".into()], crate::artifacts::MAX_BYTES)
            .await?;
    let (width, height) = dimensions(&png)?;
    Ok((png, width, height))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_text_and_invalid_png_dimensions() {
        assert!(dimensions(b"Permission denied").is_err());
        let mut png = vec![0; 33];
        png[..8].copy_from_slice(b"\x89PNG\r\n\x1a\n");
        png[11] = 13;
        png[12..16].copy_from_slice(b"IHDR");
        png[16..20].copy_from_slice(&1920u32.to_be_bytes());
        png[20..24].copy_from_slice(&1080u32.to_be_bytes());
        assert_eq!(dimensions(&png).unwrap(), (1920, 1080));
        png[16..20].fill(0);
        assert!(dimensions(&png).is_err());
    }
}
