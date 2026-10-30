plugins { `java-library`; alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":shared"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
val buildHostRuntime = rootProject.tasks.named("buildHostRuntime")
tasks.withType<Test>().configureEach {
    dependsOn(buildHostRuntime)
    inputs.file(rootProject.file("runtime/target/debug/airc-runtime"))
    systemProperty("airc.hostRuntime", rootProject.file("runtime/target/debug/airc-runtime").absolutePath)
}
