// Core module: the TLP frame codec (net.lockney.tabula.core.tlp) and the
// generated settings classes (net.lockney.tabula.core.settings).
//
// Plain Kotlin JVM on purpose (packet P2.0): the codec must not depend on the
// Android framework so it can be tested on a plain JVM against
// schema/fixtures/, the same golden fixtures the mock device and the
// integration use (plan section 8). The app module consumes it as a library.
//
// Note for P2.1: java.time is used for date-time fields, which needs
// coreLibraryDesugaring in the app module for minSdk 24.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Both compilers target 17 explicitly: Gradle defaults compileJava to the
// running JDK, and the Kotlin plugin fails the build when that differs from
// jvmTarget (issue #56). No toolchain: it would need a local JDK 17 on every
// machine, and auto-provisioning is a release download (AGENTS.md rule 7).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
