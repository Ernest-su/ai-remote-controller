plugins { alias(libs.plugins.android.application) }
android {
    namespace="ernest.airc.mediafixture"
    compileSdk=36
    defaultConfig {applicationId="ernest.airc.mediafixture";minSdk=26;targetSdk=28;versionCode=1;versionName="fixture"}
    buildTypes { create("updateFixture") { initWith(getByName("debug")); signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
}

androidComponents {
    onVariants(selector().withBuildType("updateFixture")) { variant ->
        variant.outputs.forEach { it.versionCode.set(2); it.versionName.set("fixture-update") }
    }
}
