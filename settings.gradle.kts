pluginManagement {
    repositories {
        google()
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

rootProject.name = "harness"

include(":app")

// The terminal comes from the vendored fork as a source dependency rather than a
// published AAR, so a change there is one build away from running.
include(":terminal-library")
project(":terminal-library").projectDir =
    file("vendor/ghostty-android/android/terminal-library")
