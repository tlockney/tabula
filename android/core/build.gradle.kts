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
