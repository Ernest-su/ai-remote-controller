plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "ernest.airc"
    compileSdk = 36
    defaultConfig {
        applicationId = "ernest.airc"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { jvmToolchain(17) }

// A distributable APK must carry the actual target binaries, never an empty stub.
val bundleRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build-runtime.py")
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
    inputs.files(rootProject.fileTree("runtime/src"), rootProject.file("runtime/Cargo.toml"),
        rootProject.file("runtime/Cargo.lock"), rootProject.file("scripts/build-runtime.py"))
    outputs.dir(layout.projectDirectory.dir("src/main/assets/runtime"))
}
tasks.named("preBuild") { dependsOn(bundleRuntime) }

val buildHostRuntime = rootProject.tasks.named("buildHostRuntime")
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(buildHostRuntime)
    inputs.file(rootProject.file("runtime/target/debug/airc-runtime"))
    systemProperty("airc.hostRuntime", rootProject.file("runtime/target/debug/airc-runtime").absolutePath)
    providers.gradleProperty("aircReleaseFixture").orNull?.let { systemProperty("airc.releaseFixture", it) }
}

providers.gradleProperty("aircReleaseFixture").orNull?.let { manifestPath ->
    val fixtures = layout.buildDirectory.dir("release-signature-fixtures")
    val copyReleaseFixture by tasks.registering(Copy::class) {
        from(manifestPath) { rename { "manifest.json" } }
        from("$manifestPath.sig") { rename { "manifest.json.sig" } }
        into(fixtures)
    }
    android.sourceSets.getByName("androidTest").assets.directories.add(fixtures.get().asFile.absolutePath)
    tasks.configureEach { if (name == "mergeDebugAndroidTestAssets") dependsOn(copyReleaseFixture) }
}

val scrcpyAssets = layout.buildDirectory.dir("generated/scrcpyAssets")
val prepareScrcpyServer by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("scripts/prepare-scrcpy-server.py"), scrcpyAssets.get().asFile)
    inputs.file(rootProject.file("scripts/prepare-scrcpy-server.py"))
    outputs.file(scrcpyAssets.map { it.file("scrcpy-server-v4.0") })
}
android.sourceSets.getByName("main").assets.directories.add(scrcpyAssets.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(prepareScrcpyServer) }

dependencies {
    implementation(project(":shared"))
    implementation(project(":client"))
    implementation(project(":ascrcpy"))
    implementation(project(":sharedUi"))
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Compose instrumentation's exception collector must share the app's coroutine
    // class loader; a provider in the separate test APK is not always visible.
    debugImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.github.Ernest-su:adb:v0.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
