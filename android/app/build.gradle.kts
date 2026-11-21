// The kiosk application module. From P2.1 this hosts the foreground service
// and UI; until then it only wires the core module into an Android build so
// `./gradlew test` exercises the Android toolchain in CI.
//
// minSdk per ADR 0001 (Android 7 covers the later PF1007L targets); targetSdk
// and compileSdk per ADR 0008.

plugins {
    alias(libs.plugins.android.application)
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}
