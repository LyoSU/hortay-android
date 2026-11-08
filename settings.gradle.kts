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
        // Same filter as pluginManagement: only Google-hosted groups are looked up on
        // Google Maven, so every other artifact skips a guaranteed-miss round-trip
        // there (and can't be shadowed by it). Groups outside the filter fall through
        // to mavenCentral as before.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Hortay"
include(":app")
include(":libtdlib")
include(":libwebm")
include(":baselineprofile")
