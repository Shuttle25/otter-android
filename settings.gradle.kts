pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://build-artifacts.signal.org/libraries/maven/") { content { includeGroup("org.signal") } }
    }
}
rootProject.name = "otter-android"
include(
    ":app",
    ":core:proto", ":core:crypto", ":core:signal", ":core:network", ":core:database", ":core:data", ":core:ui",
    ":feature:onboarding", ":feature:chats", ":feature:conversation", ":feature:groups",
)
