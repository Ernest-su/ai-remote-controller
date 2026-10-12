plugins { `java-library`; kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
val buildHostRuntime = rootProject.tasks.named("buildHostRuntime")
tasks.withType<Test>().configureEach {
    dependsOn(buildHostRuntime)
    inputs.file(rootProject.file("runtime/target/debug/airc-runtime"))
    systemProperty("airc.hostRuntime", rootProject.file("runtime/target/debug/airc-runtime").absolutePath)
}
