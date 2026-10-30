plugins { alias(libs.plugins.android.application) }
android {
    namespace = "ernest.airc.inputfixture"
    compileSdk = 36
    defaultConfig { applicationId = "ernest.airc.inputfixture"; minSdk = 24; targetSdk = 28; versionCode = 1; versionName = "fixture" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
