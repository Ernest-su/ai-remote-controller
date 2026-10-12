pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
rootProject.name = "AI Remote Controller"
include(":shared", ":client", ":sharedUi", ":androidApp", ":desktopApp")

// Disposable target media integration fixture, excluded from runtime/APK distribution.
include(":mediaFixture")
include(":ascrcpy")
