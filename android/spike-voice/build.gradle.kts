// The voice spike module (packet P7.0): microphone capture, the audio-source
// probe, echo cancellation and the wake-word cost run.
//
// It is a separate module on purpose, not a flavor of :app. It has its own
// applicationId, so installing it never touches the paired production app
// (net.lockney.tabula), its data or its pairing; and it carries none of the
// production service, dashboard or credentials. It is never assembled for
// release: `make voice-spike-apk` builds the debug APK only, and no release
// pipeline includes this module. The packet's Result section and the P7.0 ADR
// record whether it is deleted or kept after the spike.
//
// The TFLite runtime comes from Maven Central, like every other dependency
// (AGENTS.md rule 7). The wake-word model is not committed: the device script
// fetches it into build/ and records its sha256 and license (see the ADR).

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.lockney.tabula.spike.voice"
    compileSdk = 34

    defaultConfig {
        // Never change this to the production id: the spike must install
        // alongside net.lockney.tabula and never replace it.
        applicationId = "net.lockney.tabula.spike.voice"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Unused: nothing builds a release variant of this module.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.tensorflow.lite)
    testImplementation(kotlin("test"))
}
