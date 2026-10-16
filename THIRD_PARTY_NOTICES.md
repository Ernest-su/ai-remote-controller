# Third-party notices

AIRC original code is licensed under the MIT License in the root `LICENSE`.
This does not replace third-party copyright notices or licenses.

## Included source and server

- **ascrcpy**: adapted from Ernest-su/ascrcpy at commit
  `4c51086fb4df629915821af417ebc79f4c48c9f4` (see the authoritative revision
  in `ascrcpy/UPSTREAM.md`). Apache License 2.0; the original namespaces,
  source notices and full license remain in `ascrcpy/LICENSE`.
- **scrcpy server 4.0**: https://github.com/Genymobile/scrcpy/tree/v4.0
  Copyright (C) 2018 Genymobile; Copyright (C) 2018–2026 Romain Vimont.
  Apache License 2.0. The server is downloaded without modification and checked
  against its pinned SHA-256 before inclusion. Source and license:
  https://github.com/Genymobile/scrcpy/blob/v4.0/LICENSE

The APK's `assets/licenses/` and release attachments include the project license,
these notices and the Apache-2.0 license text. The source revision for adapted
ascrcpy code is recorded in `ascrcpy/UPSTREAM.md`; modifications provide AIRC's
ADB transport, surface handling and lifecycle integration.

## Dependencies and references

AndroidX, Compose, Kotlin, OkHttp, ZXing, the ADB dependency, and Rust crates are
separate dependencies with their own licenses. Exact direct versions are in
Gradle build files and `runtime/Cargo.toml`; Rust resolution is in
`runtime/Cargo.lock`. Their upstream notices and license terms continue to apply.
This file identifies included third-party source/server and does not purport to
be a complete inventory of every transitive dependency.

The visual guidelines reference Tencent WeUI. AIRC uses its own native Compose
implementation; the reference does not imply endorsement by Tencent.
