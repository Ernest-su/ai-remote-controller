use std::{env, fs, path::PathBuf, process::Command};
fn main() {
    println!("cargo:rerun-if-changed=platform/android/ernest/airc/bridge");
    println!("cargo:rerun-if-changed=../scripts/build-platform-bridge.py");
    for key in [
        "ANDROID_HOME",
        "ANDROID_SDK_ROOT",
        "JAVA_HOME",
        "AIRC_PYTHON",
    ] {
        println!("cargo:rerun-if-env-changed={key}");
    }
    let output = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        let status = Command::new(env::var_os("AIRC_PYTHON").unwrap_or_else(|| {
            if cfg!(windows) {
                "python".into()
            } else {
                "python3".into()
            }
        }))
        .arg("../scripts/build-platform-bridge.py")
        .arg(&output)
        .status()
        .expect("run platform bridge compiler");
        assert!(
            status.success(),
            "Android platform bridge compilation failed"
        );
    } else {
        fs::write(output.join("classes.dex"), []).unwrap();
    }
}
