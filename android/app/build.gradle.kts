// The kiosk application module: the foreground service, boot receiver, pairing
// screen, Keystore-backed credential storage and the device sensors (packet
// P2.1). The protocol state machine lives in :ha; the codec and generated
// settings in :core.
//
// minSdk 24 per plan section 3, "Baselines" (Android 7 keeps the later
// PF1007L targets reachable); targetSdk and compileSdk per ADR 0008.
// coreLibraryDesugaring is required because :core uses java.time, which
// predates API 26 (ADR 0008).

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        isCoreLibraryDesugaringEnabled = true
    }
}

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
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
