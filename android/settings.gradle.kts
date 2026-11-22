// Tabula Android build. Toolchain versions and repositories are pinned in
// gradle/libs.versions.toml; the reasoning is in
// docs/decisions/0008-android-toolchain-and-targetsdk.md.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tabula"

include(":app")
include(":core")
include(":ha")
// The P7.0 voice spike: a separate module with its own applicationId, built as
// a debug APK only (never part of a release). See the P7.0 ADR.
include(":spike-voice")
