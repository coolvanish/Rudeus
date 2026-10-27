// :cli — JVM entry point that runs the same CharacterPipeline / SceneBuilder as the phone,
// against FixtureApi (offline) or the real Meta API (key from META_API_KEY env or local.properties).
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("kz.rudy.ar.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    testImplementation(libs.kotlin.test)
}

tasks.named<JavaExec>("run") {
    environment("META_API_KEY", System.getenv("META_API_KEY") ?: "")
    workingDir = rootProject.projectDir
}
