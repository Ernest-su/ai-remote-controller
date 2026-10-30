plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// A single owner avoids overlapping outputs between Android and JVM test tasks.
val buildHostRuntime by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine("cargo", "build", "--locked", "--manifest-path", "runtime/Cargo.toml")
    inputs.files(fileTree("runtime/src"), file("runtime/Cargo.toml"), file("runtime/Cargo.lock"))
    outputs.file(file("runtime/target/debug/airc-runtime"))
}
