import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core — pure Kotlin domain. NO Compose, NO Android UI, NO ARCore types.
// Targets: android (consumed by :shared/:androidApp) and jvm (consumed by :cli and run by tests).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    // HAND-REVIEW: v2 iOS readiness (ai/build-plan-v2.md) — :core also compiles for iOS (device + Apple-silicon simulator).
    // Klibs only: no framework is declared here, the Xcode-facing umbrella comes with the iOS app shell (T51).
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "kz.rudy.ar.core"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // HAND-REVIEW: T39 — one common file implementation (io/Io*.kt) for android, jvm and ios instead of a third copy;
            // declared explicitly instead of leaning on Ktor's transitive kotlinx-io (same 0.9.1, the APK gains nothing).
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
