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

rootProject.name = "Vynox"

// Pure Kotlin editing engine (no Android dependency: fast unit tests, portable core)
include(":core:json")
include(":core:math")
include(":core:animation")
include(":core:model")
include(":core:effects")
include(":core:composition")
include(":core:timeline")
include(":core:vnx")

// Android application shell: UI, decoders, OpenGL renderer, encoder
include(":app")
