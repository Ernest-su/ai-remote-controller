plugins { id("com.android.application") }
android {
    namespace = "ernest.airc.ime"
    compileSdk = 36
    defaultConfig { applicationId = "ernest.airc.ime"; minSdk = 26; targetSdk = 35; versionCode = 4; versionName = "0.1.3" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            storeType = "PKCS12"
            storeFile = providers.environmentVariable("AIRC_ANDROID_KEYSTORE").orNull?.let { file(it) }
            storePassword = providers.environmentVariable("AIRC_ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("AIRC_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("AIRC_ANDROID_KEY_PASSWORD").orNull
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}
