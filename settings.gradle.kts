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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "omalateral"
include(":app")
include(":omalauncher", ":owl")
project(":omalauncher").projectDir = file("third_party/omalauncher/app")
project(":omalauncher").buildFileName = "omalateral.gradle.kts"
project(":owl").projectDir = file("third_party/omalauncher/owl")
project(":owl").buildFileName = "omalateral.gradle.kts"
includeBuild("third_party/florisboard") {
    dependencySubstitution {
        substitute(module("com.lateral.thirdparty:florisboard-embedded"))
            .using(project(":embedded"))
    }
}
