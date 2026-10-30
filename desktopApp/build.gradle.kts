import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}
kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        jvmMain {
            resources.srcDir(rootProject.file("androidApp/src/main/res"))
            dependencies {
                implementation(project(":shared"))
                implementation(project(":client"))
                implementation(project(":sharedUi"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(libs.coroutines.swing)
                implementation(libs.serialization.json)
                implementation(libs.okhttp)
                implementation(libs.jmdns)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
compose.desktop {
    application { mainClass = "ernest.airc.desktop.MainKt" }
}
tasks.withType<JavaExec>().configureEach { workingDir(rootProject.projectDir) }

tasks.named<ProcessResources>("jvmProcessResources") {
    filesMatching("drawable/*.xml") { filter { it.replace("@android:color/transparent", "#00000000") } }
}
tasks.withType<Test>().configureEach {
    providers.gradleProperty("aircTargetSerial").orNull?.let {
        systemProperty("airc.targetSerial", it)
        systemProperty("airc.runtimeAssets",rootProject.file("androidApp/src/main/assets/runtime").absolutePath)
        systemProperty("airc.deploymentScript",rootProject.file("android-build-run.sh").absolutePath)
        dependsOn(":androidApp:assembleDebug", ":mediaFixture:assembleDebug", ":mediaFixture:assembleUpdateFixture")
        systemProperty("airc.mediaFixtureApk",rootProject.file("mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk").absolutePath)
        systemProperty("airc.mediaUpdateFixtureApk",rootProject.file("mediaFixture/build/outputs/apk/updateFixture/mediaFixture-updateFixture.apk").absolutePath)
        // Real target state is external to Gradle inputs; opt-in always performs the check.
        outputs.upToDateWhen { false }
    }
}

tasks.withType<Test>().configureEach {providers.gradleProperty("aircVendorMediaProbe").orNull?.let {systemProperty("airc.vendorMediaProbe",it)} }

val buildSplitFixtures by tasks.registering(Exec::class) {
    commandLine("python3", rootProject.file("scripts/build-split-fixtures.py").absolutePath)
    inputs.file(rootProject.file("scripts/build-split-fixtures.py"))
    outputs.dir(rootProject.file("build/split-fixtures"))
}
tasks.withType<Test>().configureEach {
    if(providers.gradleProperty("aircTargetSerial").isPresent) {
        dependsOn(buildSplitFixtures)
        systemProperty("airc.splitFixtures",rootProject.file("build/split-fixtures").absolutePath)
    }
}

// Share the release version with the Android controller and companion IME.
val appVersionName = libs.versions.app.name.get()
val appVersionCode = libs.versions.app.code.get()
val generateProjectVersion by tasks.registering {
    inputs.property("versionName", appVersionName)
    inputs.property("versionCode", appVersionCode)
    val destination = layout.buildDirectory.dir("generated/projectVersion")
    outputs.dir(destination)
    doLast {
        destination.get().file("airc-version.txt").asFile.apply {
            parentFile.mkdirs()
            writeText("$appVersionName ($appVersionCode)")
        }
    }
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(generateProjectVersion) }
