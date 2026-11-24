// The kiosk application module: the foreground service, boot receiver, pairing
// screen, Keystore-backed credential storage and the device sensors (packet
// P2.1). The protocol state machine lives in :ha; the codec and generated
// settings in :core.
//
// minSdk 24 per plan section 3, "Baselines" (Android 7 keeps the later
// PF1007L targets reachable); targetSdk and compileSdk per ADR 0008.
// coreLibraryDesugaring is required because :core uses java.time, which
// predates API 26 (ADR 0008).

import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// --- the wake-word model (ADR 0030 item 8) -----------------------------------
// The model is not in git. It is fetched at build time from microWakeWord's
// model repository at a pinned commit, checked against the hash below and
// copied into the generated assets directory, so a build after the first is
// offline. A wrong hash fails the build rather than shipping another model.
val wakeWordUrl =
    "https://raw.githubusercontent.com/esphome/micro-wake-word-models/" +
        "40ff33f57f8fc6ad71a75ef085abbf742495b225/models/v2/okay_nabu.tflite"
val wakeWordSha256 = "0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891"
val wakeWordAssets = layout.buildDirectory.dir("generated/wakeword/assets")

val fetchWakeWordModel by tasks.registering {
    description = "Fetches the pinned wake-word model and writes its NOTICE."
    outputs.dir(wakeWordAssets)
    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }

        val directory = wakeWordAssets.get().asFile.resolve("wakeword")
        directory.mkdirs()
        val target = directory.resolve("okay_nabu.tflite")

        // Reuse a model already on disk, so only the first build needs network.
        if (!target.isFile || sha256(target) != wakeWordSha256) {
            logger.lifecycle("wake-word: fetching $wakeWordUrl")
            URI.create(wakeWordUrl).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256(target)
            if (actual != wakeWordSha256) {
                target.delete()
                throw GradleException(
                    "wake-word model sha256 is $actual, expected $wakeWordSha256 (ADR 0030)",
                )
            }
        }
        directory.resolve("NOTICE.txt").writeText(
            """
            okay_nabu.tflite - the microWakeWord v2 wake-word model.

            Copyright the Open Home Foundation and contributors.
            Source: https://github.com/esphome/micro-wake-word-models,
            commit 40ff33f57f8fc6ad71a75ef085abbf742495b225, models/v2/okay_nabu.tflite.
            Licensed under the Apache License, Version 2.0; see docs/THIRD-PARTY.md.

            sha256 $wakeWordSha256
            """.trimIndent() + "\n",
        )
    }
}

android {
    namespace = "net.lockney.tabula"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.lockney.tabula"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets.getByName("main").assets.srcDir(wakeWordAssets)

    // One APK per ABI (P9.3, ADR 0028): the WebRTC native library is the bulk
    // of an intercom build, and a universal APK would carry it four times. v1
    // device types are 64-bit and 32-bit ARM, so those are the splits; there is
    // no universal APK on purpose.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

tasks.named("preBuild") { dependsOn(fetchWakeWordModel) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ha"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // the wake-word interpreter (P7.1, ADR 0030): the model runs in the app's
    // foreground service, so the runtime is a production dependency now
    implementation(libs.tensorflow.lite)
    // the intercom call engine's audio (P9.3, ADR 0028): the same artifact the
    // P9.0 spike measured, from Maven Central
    implementation(libs.webrtc.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
