plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    kotlin("jvm") version "2.2.10" apply false
    kotlin("multiplatform") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
}

// A single owner avoids overlapping outputs between Android and JVM test tasks.
val buildHostRuntime by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine("cargo", "build", "--locked", "--manifest-path", "runtime/Cargo.toml")
    inputs.files(fileTree("runtime/src"), file("runtime/Cargo.toml"), file("runtime/Cargo.lock"))
    outputs.file(file("runtime/target/debug/airc-runtime"))
}
