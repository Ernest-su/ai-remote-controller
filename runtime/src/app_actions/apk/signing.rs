use super::manifest::{u16_at, u32_at};
use anyhow::{bail, Context, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(in crate::app_actions) struct Signature {
    pub scheme: String,
    pub certificate_sha256: Vec<String>,
    pub certificate_extraction_supported: bool,
}
pub(super) struct Directory {
    pub offset: u64,
    pub size: u64,
    pub entries: usize,
}
fn u64_at(data: &[u8], offset: usize) -> Result<u64> {
    Ok(u64::from_le_bytes(
        data.get(offset..offset.checked_add(8).context("offset overflow")?)
            .context("truncated uint64")?
            .try_into()?,
    ))
}
pub(super) fn directory(file: &mut File, size: u64) -> Result<Directory> {
    let count = size.min(65557) as usize;
    file.seek(SeekFrom::End(-(count as i64)))?;
    let mut tail = vec![0; count];
    file.read_exact(&mut tail)?;
    for pos in (0..=count.saturating_sub(22)).rev() {
        if tail.get(pos..pos + 4) != Some(&[0x50, 0x4b, 0x05, 0x06]) {
            continue;
        }
        if pos + 22 + u16_at(&tail, pos + 20)? as usize != count {
            continue;
        }
        if u16_at(&tail, pos + 4)? != 0 || u16_at(&tail, pos + 6)? != 0 {
            bail!("multi-disk APK not supported");
        }
        let entries = u16_at(&tail, pos + 10)? as usize;
        if entries == 0 || entries > 4096 || u16_at(&tail, pos + 8)? as usize != entries {
            bail!("APK entry count exceeds limit");
        }
        let length = u32_at(&tail, pos + 12)? as u64;
        let offset = u32_at(&tail, pos + 16)? as u64;
        if length > 8 * 1024 * 1024
            || offset.checked_add(length) != Some(size - count as u64 + pos as u64)
        {
            bail!("invalid APK central directory bounds");
        }
        return Ok(Directory {
            offset,
            size: length,
            entries,
        });
    }
    bail!("APK ZIP directory missing")
}
fn prefixed<'a>(bytes: &mut &'a [u8]) -> Result<&'a [u8]> {
    let size = u32_at(bytes, 0)? as usize;
    let value = bytes
        .get(
            4..4usize
                .checked_add(size)
                .context("signing length overflow")?,
        )
        .context("truncated signing record")?;
    *bytes = &bytes[4 + size..];
    Ok(value)
}
fn certificates(value: &[u8]) -> Result<Vec<String>> {
    let mut outer = value;
    let mut signers = prefixed(&mut outer)?;
    if !outer.is_empty() {
        bail!("trailing signer data");
    }
    let mut result = Vec::new();
    while !signers.is_empty() {
        if result.len() >= 16 {
            bail!("too many APK signers");
        }
        let mut signer = prefixed(&mut signers)?;
        let mut signed = prefixed(&mut signer)?;
        let _digests = prefixed(&mut signed)?;
        let mut chain = prefixed(&mut signed)?;
        let certificate = prefixed(&mut chain)?;
        if certificate.is_empty() || certificate.len() > 128 * 1024 || certificate[0] != 0x30 {
            bail!("invalid declared APK certificate");
        }
        // The first certificate identifies the signer. Cryptographic verification
        // and certificate/public-key matching are deliberately not claimed here.
        result.push(format!("{:x}", Sha256::digest(certificate)));
    }
    if result.is_empty() {
        bail!("APK signers missing");
    }
    Ok(result)
}
pub(super) fn inspect(file: &mut File, directory: &Directory) -> Result<Vec<Signature>> {
    if directory.offset < 24 {
        return Ok(vec![]);
    }
    file.seek(SeekFrom::Start(directory.offset - 24))?;
    let mut footer = [0; 24];
    file.read_exact(&mut footer)?;
    if &footer[8..] != b"APK Sig Block 42" {
        return Ok(vec![]);
    }
    let size = u64_at(&footer, 0)?;
    if !(24..=8 * 1024 * 1024).contains(&size) {
        bail!("APK signing block exceeds limit");
    }
    let start = directory
        .offset
        .checked_sub(size + 8)
        .context("invalid signing block offset")?;
    file.seek(SeekFrom::Start(start))?;
    let mut block = vec![0; (size + 8) as usize];
    file.read_exact(&mut block)?;
    if u64_at(&block, 0)? != size {
        bail!("APK signing block sizes differ");
    }
    let mut offset = 8;
    let end = block.len() - 24;
    let mut pairs = 0;
    let mut result = Vec::new();
    let mut seen = std::collections::HashSet::new();
    while offset < end {
        pairs += 1;
        if pairs > 128 {
            bail!("too many APK signing records");
        }
        let length = usize::try_from(u64_at(&block, offset)?)?;
        if length < 4 || length > end.saturating_sub(offset + 8) {
            bail!("invalid signing pair length");
        }
        let id = u32_at(&block, offset + 8)?;
        let scheme = match id {
            0x7109871a => Some("v2"),
            0xf05368c0 => Some("v3"),
            0x1b93ad61 => Some("v3.1"),
            _ => None,
        };
        if let Some(scheme) = scheme {
            if !seen.insert(id) {
                bail!("duplicate APK signature scheme");
            }
            let value = &block[offset + 12..offset + 8 + length];
            result.push(Signature {
                scheme: scheme.into(),
                certificate_sha256: certificates(value)?,
                certificate_extraction_supported: true,
            });
        }
        offset += 8 + length;
    }
    result.sort_by(|a, b| a.scheme.cmp(&b.scheme));
    Ok(result)
}
#[cfg(test)]
pub(super) fn certificate_test(value: &[u8]) -> Result<Vec<String>> {
    certificates(value)
}
