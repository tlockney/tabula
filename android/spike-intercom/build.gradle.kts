// P9.0's throwaway intercom spike: a separate application, so installing it
// never replaces, uninstalls or unpairs net.lockney.tabula (packet
// docs/packets/P9.0-intercom-webrtc-spike.md). Its own applicationId is the
// guarantee; the release variant is disabled so a release build of this
// module cannot exist at all.
//
// The WebRTC artifact and its reasoning are in ADR 0028; ABI splits exist so
// the packet can report the APK size added per ABI.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.lockney.tabula.spike.intercom"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.lockney.tabula.spike.intercom"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.9.0-spike"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    // One split per ABI, plus a universal APK for a device whose ABI is not
    // obvious. The packet's size table comes from these.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Debug only: no release variant exists to ship (packet "Do" 1).
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation(libs.webrtc.android)
    implementation(libs.kotlinx.coroutines.core)
}
