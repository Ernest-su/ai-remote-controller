plugins { alias(libs.plugins.android.library) }
android {
    namespace = "ernest.ascrcpy.scrcpy"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(libs.adb)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
