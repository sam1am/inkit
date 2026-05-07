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
        // Onyx Boox vendor SDK (TouchHelper / EpdController) — required by
        // OnyxInkController. Public read-only Maven mirror published by Onyx.
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "inkit"
include(":app")
include(":inksdk")
project(":inksdk").projectDir = file("inksdk")