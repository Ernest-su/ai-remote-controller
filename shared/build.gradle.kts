plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
        commonMain.dependencies { implementation(libs.coroutines.core); implementation(libs.serialization.json) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
