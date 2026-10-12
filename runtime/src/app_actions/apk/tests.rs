use super::*;
use std::io::{Cursor, Write};
fn word(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_le_bytes());
}
fn chunk(kind: u16, header: u16, body: Vec<u8>) -> Vec<u8> {
    let mut out = vec![];
    out.extend_from_slice(&kind.to_le_bytes());
    out.extend_from_slice(&header.to_le_bytes());
    word(&mut out, (body.len() + 8) as u32);
    out.extend(body);
    out
}
fn xml(utf8: bool) -> Vec<u8> {
    let pool = [
        "manifest",
        "package",
        "com.test.app",
        "http://schemas.android.com/apk/res/android",
        "versionCode",
        "versionCodeMajor",
        "versionName",
        "测试😀",
    ];
    let mut offsets = vec![];
    let mut data = vec![];
    for text in pool {
        word(&mut offsets, data.len() as u32);
        if utf8 {
            data.push(text.encode_utf16().count() as u8);
            data.push(text.len() as u8);
            data.extend_from_slice(text.as_bytes());
            data.push(0);
        } else {
            data.extend_from_slice(&(text.encode_utf16().count() as u16).to_le_bytes());
            for unit in text.encode_utf16() {
                data.extend_from_slice(&unit.to_le_bytes());
            }
            data.extend_from_slice(&[0, 0]);
        }
    }
    let mut body = vec![];
    word(&mut body, pool.len() as u32);
    word(&mut body, 0);
    word(&mut body, if utf8 { 256 } else { 0 });
    word(&mut body, 28 + offsets.len() as u32);
    word(&mut body, 0);
    body.extend(offsets);
    body.extend(data);
    let strings = chunk(1, 28, body);
    let mut element = vec![];
    word(&mut element, 1);
    word(&mut element, u32::MAX);
    word(&mut element, u32::MAX);
    word(&mut element, 0);
    element.extend_from_slice(&20u16.to_le_bytes());
    element.extend_from_slice(&20u16.to_le_bytes());
    element.extend_from_slice(&4u16.to_le_bytes());
    element.extend_from_slice(&[0; 6]);
    for (ns, name, kind, value) in [
        (u32::MAX, 1, 3, 2),
        (3, 4, 16, 42),
        (3, 5, 16, 2),
        (3, 6, 3, 7),
    ] {
        word(&mut element, ns);
        word(&mut element, name);
        word(&mut element, u32::MAX);
        element.extend_from_slice(&8u16.to_le_bytes());
        element.push(0);
        element.push(kind);
        word(&mut element, value);
    }
    let mut document = strings;
    let mut resource_map = vec![];
    for id in [0, 0, 0, 0, 0x0101021b, 0x01010576, 0x0101021c, 0] {
        word(&mut resource_map, id);
    }
    document.extend(chunk(0x0180, 8, resource_map));
    document.extend(chunk(0x0102, 16, element));
    chunk(3, 8, document)
}
#[test]
fn manifest_reads_namespace_versions_utf8_and_utf16_with_bounds() {
    for utf8 in [false, true] {
        let data = xml(utf8);
        let info = manifest::parse(&data).unwrap();
        assert_eq!(info.package, "com.test.app");
        assert_eq!(info.version_code, (2u64 << 32) | 42);
        assert_eq!(info.version_name.as_deref(), Some("测试😀"));
        for size in 0..data.len() {
            assert!(manifest::parse(&data[..size]).is_err());
        }
        let mut broken = data.clone();
        broken[8 + 20..8 + 24].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(manifest::parse(&broken).is_err());
    }
}
fn prefix(data: &[u8]) -> Vec<u8> {
    let mut out = vec![];
    word(&mut out, data.len() as u32);
    out.extend_from_slice(data);
    out
}
#[test]
fn signing_fingerprints_only_declared_leaf_and_rejects_truncation() {
    let cert = [0x30, 0x02, 0x01, 0x00];
    let mut signed = prefix(&[]);
    signed.extend(prefix(&prefix(&cert)));
    signed.extend(prefix(&[]));
    let mut signer = prefix(&signed);
    signer.extend(prefix(&[]));
    signer.extend(prefix(&[]));
    let value = prefix(&prefix(&signer));
    assert_eq!(
        signing::certificate_test(&value).unwrap(),
        vec![format!("{:x}", Sha256::digest(cert))]
    );
    for size in 0..value.len() {
        assert!(signing::certificate_test(&value[..size]).is_err());
    }
}
#[test]
fn zip_inspection_hashes_exact_file_and_rejects_duplicate_manifest() {
    for duplicate in [false, true] {
        let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for index in 0..if duplicate { 2 } else { 1 } {
            zip.start_file(
                if index == 0 {
                    "AndroidManifest.xml"
                } else {
                    "AndroidManifest.xmL"
                },
                zip::write::SimpleFileOptions::default()
                    .compression_method(zip::CompressionMethod::Deflated),
            )
            .unwrap();
            zip.write_all(&xml(true)).unwrap();
        }
        let mut bytes = zip.finish().unwrap().into_inner();
        if duplicate {
            let name = b"AndroidManifest.xmL";
            for offset in 0..=bytes.len() - name.len() {
                if &bytes[offset..offset + name.len()] == name {
                    bytes[offset + name.len() - 1] = b'l';
                }
            }
        }
        let path = std::env::temp_dir().join(format!("airc-apk-inspect-{}", uuid::Uuid::new_v4()));
        let mut file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        file.write_all(&bytes).unwrap();
        let progress = AtomicU64::new(0);
        let result = inspect(
            file,
            "inbox".into(),
            "fixture.apk".into(),
            &AtomicBool::new(false),
            &progress,
            0,
        );
        let cancelled = inspect(
            File::open(&path).unwrap(),
            "inbox".into(),
            "fixture.apk".into(),
            &AtomicBool::new(true),
            &AtomicU64::new(0),
            0,
        );
        assert!(cancelled.err().unwrap().to_string().contains("cancelled"));
        assert_eq!(std::fs::metadata(&path).unwrap().len(), bytes.len() as u64);
        std::fs::remove_file(path).unwrap();
        if duplicate {
            assert!(result.is_err());
        } else {
            assert_eq!(progress.load(Ordering::Relaxed), bytes.len() as u64);
            let info = result.unwrap();
            assert_eq!(info.sha256, format!("{:x}", Sha256::digest(&bytes)));
            assert_eq!(info.package, "com.test.app");
            assert!(!info.signature_verified);
            assert!(info.signatures.is_empty());
        }
    }
}

#[test]
fn aliased_pool_strings_cannot_expand_memory_without_bound() {
    let count = 1024u32;
    let mut body = vec![];
    word(&mut body, count);
    word(&mut body, 0);
    word(&mut body, 256);
    word(&mut body, 28 + count * 4);
    word(&mut body, 0);
    for _ in 0..count {
        word(&mut body, 0);
    }
    body.extend_from_slice(&[0xff, 0xff, 0xff, 0xff]);
    body.extend(std::iter::repeat_n(b'a', 32767));
    body.push(0);
    let data = chunk(3, 8, chunk(1, 28, body));
    let error = manifest::parse(&data).unwrap_err().to_string();
    assert!(error.contains("decoded string pool exceeds"), "{error}");
}

#[test]
fn version_resource_identity_must_match_and_missing_code_defaults_to_zero() {
    let data = xml(true);
    let pool_size = manifest::u32_at(&data, 12).unwrap() as usize;
    let resource_offset = 8 + pool_size;
    let mut broken = data.clone();
    broken[resource_offset + 8 + 4 * 4..resource_offset + 12 + 4 * 4]
        .copy_from_slice(&0x0101021cu32.to_le_bytes());
    assert!(manifest::parse(&broken)
        .unwrap_err()
        .to_string()
        .contains("resource"));
    let resource_size = manifest::u32_at(&data, resource_offset + 4).unwrap() as usize;
    let root = resource_offset + resource_size;
    let mut missing = data.clone();
    missing.drain(root + 36 + 20..root + 36 + 60);
    missing[root + 28..root + 30].copy_from_slice(&2u16.to_le_bytes());
    let root_size = manifest::u32_at(&data, root + 4).unwrap() - 40;
    missing[root + 4..root + 8].copy_from_slice(&root_size.to_le_bytes());
    let size = missing.len() as u32;
    missing[4..8].copy_from_slice(&size.to_le_bytes());
    assert_eq!(manifest::parse(&missing).unwrap().version_code, 0);
}
