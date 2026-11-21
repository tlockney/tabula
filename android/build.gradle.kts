// Tabula Android root build file. Component modules: core (TLP codec and
// settings, plain JVM, no Android framework dependencies) and app (the kiosk
// application, from P2.1).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
