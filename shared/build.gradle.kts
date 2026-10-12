plugins {
    id("com.android.kotlin.multiplatform.library")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
kotlin {
    androidLibrary {
        namespace = "ernest.airc.shared"
        compileSdk = 36
        minSdk = 26
    }
    jvm()
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"); implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
