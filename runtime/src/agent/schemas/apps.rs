use serde_json::{json, Value};

pub(super) fn cache() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"apps_cache_capabilities","description":"Read explicit package-manager --cache-only support and runtime permission eligibility. Unsupported systems cannot clear app data as a fallback.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_clear_cache","description":"Prepare current-user non-system app cache-only cleanup requiring manual app-user confirmation. Does not clear until approved. System must explicitly advertise --cache-only; never use plain pm clear or shell to bypass confirmation. Cache may include offline files and must be redownloaded; saved app data is not intentionally cleared.","parameters":{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_cache_pending","description":"List cache-only proposals awaiting manual confirmation, without executing them.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
    ]
}

pub(super) fn management() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"apps_install","description":"Prepare installation or explicit replacement of a user-selected target APK or base plus split APK set whose inspected SHA-256 values are given. Use either path/sha256 or apks (1..32 objects with path/sha256); one base, unique splits, same package/version, at most 4 GiB total. All packages are committed atomically in one Android installation session. Returns a cancellable preparation task; completed result is a proposal requiring manual app-user confirmation, NOT an installation. Replacement may affect package code for all Android users. Never bypass confirmation via shell.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"},"sha256":{"type":"string"},"apks":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"object","properties":{"path":{"type":"string"},"sha256":{"type":"string"}},"required":["path","sha256"],"additionalProperties":false}},"update":{"type":"boolean"}},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_install_pending","description":"List prepared APK installation proposals awaiting manual app-user confirmation; does not install.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_apk_info","description":"Inspect a user-selected target APK before installation. Returns a cancellable long task; poll task_progress for completed result containing package/version, streaming SHA-256 and declared signing certificate fingerprints. Does not install and does not verify cryptographic signatures. Only Android package manager verification can establish installability.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"}},"required":["path"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_info","description":"Read package, version, app ID, system flag and current Android user installation identity.","parameters":{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_uninstall","description":"Prepare uninstall of a user application for the current Android user, including its data. Returns a proposal; does not uninstall until the app user confirms. System/privileged apps are rejected. Do not bypass confirmation via shell.","parameters":{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_pending","description":"List app uninstall proposals awaiting user confirmation.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
    ]
}

pub(super) fn launcher() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"apps_apk_icon","description":"Read a selected APK archive icon as a short-lived 96px PNG artifact. Does not install the APK or depend on an installed application. Relative path within the selected root; no symlinks.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"}},"required":["path"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_icon","description":"Read one installed application's 96px PNG icon as a short-lived binary artifact, not inline image data.","parameters":{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"apps_list","description":"List installed Android applications as JSON with package, nullable label, and system flag. Unavailable labels are null; use package names to launch.","parameters":{"type":"object","properties":{}}}}),
        json!({"type":"function","function":{"name":"apps_open","description":"Launch an installed application by package name. List apps first if unsure.","parameters":{"type":"object","properties":{"package":{"type":"string"}},"required":["package"]}}}),
    ]
}
