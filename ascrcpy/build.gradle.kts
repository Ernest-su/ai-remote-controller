plugins { id("com.android.library") }
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
    api("com.github.Ernest-su:adb:v0.3.0")
    constraints {
        api("org.conscrypt:conscrypt-android:2.8.0") {
            because("ADB's Conscrypt 2.5.2 binaries use 4 KB ELF alignment; require 16 KB support")
        }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
