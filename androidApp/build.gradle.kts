import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ernest.airc"
    compileSdk = 36
    defaultConfig {
        applicationId = "ernest.airc"
        minSdk = 26
        targetSdk = 35
        versionCode = libs.versions.app.code.get().toInt()
        versionName = libs.versions.app.name.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            storeType = "PKCS12"
            storeFile = providers.environmentVariable("AIRC_ANDROID_KEYSTORE").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("AIRC_ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("AIRC_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("AIRC_ANDROID_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { jvmToolchain(17) }

// Override with -PaircPython=/path/to/python when Python is not on PATH.
val runtimePython = providers.gradleProperty("aircPython").getOrElse(
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
)

// A distributable APK must carry the actual target binaries, never an empty stub.
val bundleRuntime by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(runtimePython, "scripts/build-runtime.py")
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
    inputs.files(rootProject.fileTree("runtime/src"), rootProject.fileTree("runtime/platform/android"),
        rootProject.file("runtime/build.rs"), rootProject.file("runtime/Cargo.toml"),
        rootProject.file("runtime/Cargo.lock"), rootProject.file("scripts/build-runtime.py"),
        rootProject.file("scripts/build-platform-bridge.py"))
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
    commandLine(runtimePython, rootProject.file("scripts/prepare-scrcpy-server.py"), scrcpyAssets.get().asFile)
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    // Compose instrumentation's exception collector must share the app's coroutine
    // class loader; a provider in the separate test APK is not always visible.
    debugImplementation(libs.coroutines.test)
    implementation(libs.serialization.json)
    implementation(libs.adb)
    implementation(libs.okhttp)
    implementation(libs.zxing)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    // Espresso 3.7 uses getSystemService for InputManager on Android 16.
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}

// Carry the project's license and retained third-party notices in distributed APKs.
val legalAssets = layout.buildDirectory.dir("generated/legalAssets")
val prepareLegalAssets by tasks.registering(Copy::class) {
    into(legalAssets.map { it.dir("licenses") })
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("ascrcpy/LICENSE")) { rename { "LICENSE-Apache-2.0.txt" } }
}
android.sourceSets.getByName("main").assets.directories.add(legalAssets.get().asFile.absolutePath)
tasks.named("preBuild") { dependsOn(prepareLegalAssets) }

// AGP can otherwise omit its signing task and emit an unsigned release APK.
val validateProductionSigning by tasks.registering {
    doLast {
        val required = listOf("AIRC_ANDROID_KEYSTORE", "AIRC_ANDROID_STORE_PASSWORD", "AIRC_ANDROID_KEY_ALIAS", "AIRC_ANDROID_KEY_PASSWORD")
        val missing = required.filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
        check(missing.isEmpty()) { "Production release signing requires: ${missing.joinToString()}" }
        check(file(providers.environmentVariable("AIRC_ANDROID_KEYSTORE").get()).isFile) { "Production signing keystore is missing" }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateProductionSigning) }

// Register the producer with AGP so packaging and lint both depend on generated assets.
abstract class PrepareImeAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun prepare() {
        fileSystem.sync {
            from(apkFile)
            rename { "airc-input-method.apk" }
            into(outputDirectory)
        }
    }
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val prepareIme = tasks.register<PrepareImeAssets>("prepare${capitalized}Ime") {
        dependsOn(":tvInputMethod:assemble$capitalized")
        apkFile.set(project(":tvInputMethod").layout.buildDirectory.file("outputs/apk/${variant.name}/tvInputMethod-${variant.name}.apk"))
        outputDirectory.set(layout.buildDirectory.dir("generated/${variant.name}ImeAssets"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(prepareIme, PrepareImeAssets::outputDirectory)
}
