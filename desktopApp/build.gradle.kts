import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("org.jmdns:jmdns:3.6.3")
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(compose.desktop.uiTestJUnit4)
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
        dependsOn(":mediaFixture:assembleDebug", ":mediaFixture:assembleUpdateFixture")
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
