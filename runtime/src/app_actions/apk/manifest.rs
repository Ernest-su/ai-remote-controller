use anyhow::{bail, Context, Result};

#[derive(Debug)]
pub(super) struct Manifest {
    pub package: String,
    pub version_code: u64,
    pub version_name: Option<String>,
    pub split: Option<String>,
}
pub(super) fn u16_at(data: &[u8], offset: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(
        data.get(offset..offset.checked_add(2).context("offset overflow")?)
            .context("truncated uint16")?
            .try_into()?,
    ))
}
pub(super) fn u32_at(data: &[u8], offset: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(
        data.get(offset..offset.checked_add(4).context("offset overflow")?)
            .context("truncated uint32")?
            .try_into()?,
    ))
}
fn length8(data: &[u8], pos: &mut usize) -> Result<usize> {
    let value = *data.get(*pos).context("truncated string length")?;
    *pos += 1;
    if value & 128 == 0 {
        return Ok(value as usize);
    }
    let next = *data.get(*pos).context("truncated string length")?;
    *pos += 1;
    Ok(((value as usize & 127) << 8) | next as usize)
}
fn length16(data: &[u8], pos: &mut usize) -> Result<usize> {
    let value = u16_at(data, *pos)?;
    *pos += 2;
    if value & 32768 == 0 {
        return Ok(value as usize);
    }
    let next = u16_at(data, *pos)?;
    *pos += 2;
    Ok(((value as usize & 32767) << 16) | next as usize)
}
fn strings(chunk: &[u8]) -> Result<Vec<String>> {
    let header = u16_at(chunk, 2)? as usize;
    if header < 28 {
        bail!("invalid string pool header");
    }
    let count = u32_at(chunk, 8)? as usize;
    let utf8 = u32_at(chunk, 16)? & 256 != 0;
    let start = u32_at(chunk, 20)? as usize;
    let styles_start = u32_at(chunk, 24)? as usize;
    let styles_count = u32_at(chunk, 12)? as usize;
    if count > 32768
        || styles_count > 32768
        || header
            .checked_add((count + styles_count) * 4)
            .context("pool overflow")?
            > start
        || start > chunk.len()
    {
        bail!("invalid string pool bounds");
    }
    let end = if styles_start == 0 {
        chunk.len()
    } else {
        styles_start
    };
    if end < start || end > chunk.len() {
        bail!("invalid string pool end");
    }
    let data = &chunk[start..end];
    let mut decoded_bytes = 0usize;
    (0..count)
        .map(|index| {
            let mut pos = u32_at(chunk, header + index * 4)? as usize;
            let value = if utf8 {
                let units = length8(data, &mut pos)?;
                let size = length8(data, &mut pos)?;
                let bytes = data
                    .get(pos..pos.checked_add(size).context("string overflow")?)
                    .context("truncated utf8 string")?;
                if data.get(pos + size) != Some(&0) {
                    bail!("missing string terminator");
                }
                let value = std::str::from_utf8(bytes)?.to_owned();
                if value.encode_utf16().count() != units {
                    bail!("invalid utf8 string length");
                }
                value
            } else {
                let size = length16(data, &mut pos)?;
                if size > 65536 {
                    bail!("string too large");
                }
                let bytes = size.checked_mul(2).context("string overflow")?;
                let raw = data
                    .get(pos..pos.checked_add(bytes).context("string overflow")?)
                    .context("truncated utf16 string")?;
                if u16_at(data, pos + bytes)? != 0 {
                    bail!("missing string terminator");
                }
                let units: Vec<_> = raw
                    .chunks_exact(2)
                    .map(|v| u16::from_le_bytes([v[0], v[1]]))
                    .collect();
                String::from_utf16(&units)?
            };
            decoded_bytes = decoded_bytes
                .checked_add(value.len())
                .context("decoded string pool overflow")?;
            if decoded_bytes > 16 * 1024 * 1024 {
                bail!("decoded string pool exceeds 16 MiB");
            }
            Ok(value)
        })
        .collect()
}
fn text(pool: &[String], index: u32) -> Result<&str> {
    pool.get(index as usize)
        .map(String::as_str)
        .context("invalid string index")
}
pub(super) fn parse(data: &[u8]) -> Result<Manifest> {
    if u16_at(data, 0)? != 3 || u16_at(data, 2)? != 8 || u32_at(data, 4)? as usize != data.len() {
        bail!("invalid binary XML document");
    }
    let mut pool = None;
    let mut resources: Option<Vec<u32>> = None;
    let mut offset = 8;
    while offset < data.len() {
        let kind = u16_at(data, offset)?;
        let header = u16_at(data, offset + 2)? as usize;
        let size = u32_at(data, offset + 4)? as usize;
        if header < 8 || size < header {
            bail!("invalid XML chunk size");
        }
        let chunk = data
            .get(offset..offset.checked_add(size).context("XML chunk overflow")?)
            .context("truncated XML chunk")?;
        match kind {
            1 => {
                if pool.is_some() {
                    bail!("duplicate XML string pool");
                }
                pool = Some(strings(chunk)?);
            }
            0x0180 => {
                let pool = pool.as_ref().context("resource map before string pool")?;
                if resources.is_some()
                    || header != 8
                    || !(size - 8).is_multiple_of(4)
                    || (size - 8) / 4 > pool.len()
                {
                    bail!("invalid XML resource map");
                }
                resources = Some(
                    (8..size)
                        .step_by(4)
                        .map(|offset| u32_at(chunk, offset))
                        .collect::<Result<Vec<_>>>()?,
                );
            }
            0x0102 => {
                let pool = pool.as_ref().context("XML strings missing")?;
                if header != 16
                    || size < 36
                    || u32_at(chunk, 16)? != u32::MAX
                    || text(pool, u32_at(chunk, 20)?)? != "manifest"
                {
                    bail!("first XML element must be manifest");
                }
                let start = u16_at(chunk, 24)? as usize;
                let stride = u16_at(chunk, 26)? as usize;
                let count = u16_at(chunk, 28)? as usize;
                if start < 20 || stride < 20 || count > 128 || 16 + start + count * stride > size {
                    bail!("invalid manifest attributes");
                }
                let mut package = None;
                let mut version_code = None;
                let mut major = 0;
                let mut version_name = None;
                let mut split = None;
                let mut seen = std::collections::HashSet::new();
                for index in 0..count {
                    let attr =
                        &chunk[16 + start + index * stride..16 + start + (index + 1) * stride];
                    let namespace = u32_at(attr, 0)?;
                    let namespace = if namespace == u32::MAX {
                        ""
                    } else {
                        text(pool, namespace)?
                    };
                    let name_index = u32_at(attr, 4)?;
                    let name = text(pool, name_index)?;
                    let resource = resources
                        .as_ref()
                        .and_then(|map| map.get(name_index as usize))
                        .copied()
                        .unwrap_or(0);
                    let expected_name = match resource {
                        0x0101021b => Some("versionCode"),
                        0x0101021c => Some("versionName"),
                        0x01010576 => Some("versionCodeMajor"),
                        _ => None,
                    };
                    let expected_resource = match name {
                        "versionCode" => Some(0x0101021b),
                        "versionName" => Some(0x0101021c),
                        "versionCodeMajor" => Some(0x01010576),
                        _ => None,
                    };
                    if let Some(expected) = expected_name {
                        if name != expected
                            || namespace != "http://schemas.android.com/apk/res/android"
                        {
                            bail!("version attribute resource identity mismatch");
                        }
                    }
                    if namespace == "http://schemas.android.com/apk/res/android"
                        && expected_resource.is_some_and(|expected| resource != expected)
                    {
                        bail!("version attribute resource mapping missing or inconsistent");
                    }
                    if !seen.insert((namespace, name)) {
                        bail!("duplicate manifest attribute");
                    }
                    if u16_at(attr, 12)? != 8 || attr[14] != 0 {
                        bail!("invalid attribute value");
                    }
                    let value = u32_at(attr, 16)?;
                    let string = || -> Result<String> {
                        if attr[15] != 3 {
                            bail!("string attribute required");
                        }
                        Ok(text(pool, value)?.into())
                    };
                    let integer = || -> Result<u32> {
                        if ![16, 17].contains(&attr[15]) {
                            bail!("integer attribute required");
                        }
                        Ok(value)
                    };
                    match (namespace, name) {
                        ("", "package") => package = Some(string()?),
                        ("", "split") => split = Some(string()?),
                        ("http://schemas.android.com/apk/res/android", "versionCode") => {
                            version_code = Some(integer()?)
                        }
                        ("http://schemas.android.com/apk/res/android", "versionCodeMajor") => {
                            major = integer()?
                        }
                        ("http://schemas.android.com/apk/res/android", "versionName")
                            if attr[15] == 3 =>
                        {
                            version_name = Some(string()?)
                        }
                        _ => {}
                    }
                }
                let package = package.context("APK package name missing")?;
                if !super::super::info::valid_package(&package) {
                    bail!("invalid APK package name");
                }
                if major > i32::MAX as u32 {
                    bail!("APK long version code exceeds signed 64-bit range");
                }
                if version_name
                    .as_ref()
                    .is_some_and(|name: &String| name.len() > 4096)
                {
                    bail!("APK version name exceeds 4 KiB");
                }
                return Ok(Manifest {
                    package,
                    version_code: ((major as u64) << 32) | version_code.unwrap_or(0) as u64,
                    version_name,
                    split,
                });
            }
            _ => {}
        }
        offset += size;
    }
    bail!("manifest element missing")
}
