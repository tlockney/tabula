// HA client module (net.lockney.tabula.ha): the TLP device-side state machine —
// pairing, token refresh, the WebSocket session (auth, register, command loop,
// ACKs, backoff, heartbeat) — in plain Kotlin with no Android framework
// dependencies, so it unit-tests on a plain JVM against a fake transport, and
// against mockdevice's Python test server over real sockets.
//
// Timing constants come from net.lockney.tabula.core.Constants (ADR 0002);
// frames are the :core codec's classes (packet P2.1).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
