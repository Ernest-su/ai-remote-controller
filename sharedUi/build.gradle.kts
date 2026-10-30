plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}
kotlin {
    androidLibrary { namespace = "ernest.airc.ui"; compileSdk = 36; minSdk = 26 }
    jvm()
    jvmToolchain(17)
    sourceSets.androidMain.dependencies { implementation(libs.androidx.activity.compose) }
    sourceSets.commonMain.dependencies {
        implementation(project(":shared"))
        implementation(libs.serialization.json)
        implementation(libs.coroutines.core)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
        implementation(libs.compose.ui)
    }
}
