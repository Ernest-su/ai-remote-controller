plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin {
    androidLibrary { namespace = "ernest.airc.ui"; compileSdk = 36; minSdk = 26 }
    jvm()
    jvmToolchain(17)
    sourceSets.commonMain.dependencies {
        implementation(project(":shared"))
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("org.jetbrains.compose.foundation:foundation:1.9.0")
        implementation("org.jetbrains.compose.material3:material3:1.9.0")
        implementation("org.jetbrains.compose.ui:ui:1.9.0")
    }
}
