plugins { id("com.android.application") }
android {
    namespace="ernest.airc.mediafixture"
    compileSdk=36
    defaultConfig {applicationId="ernest.airc.mediafixture";minSdk=26;targetSdk=28;versionCode=1;versionName="fixture"}
    buildTypes { create("updateFixture") { initWith(getByName("debug")); signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.annotation:annotation:1.9.1")
}

androidComponents {
    onVariants(selector().withBuildType("updateFixture")) { variant ->
        variant.outputs.forEach { it.versionCode.set(2); it.versionName.set("fixture-update") }
    }
}
