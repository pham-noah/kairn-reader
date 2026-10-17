pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }   // Readium is on Maven Central (BSD-3, OQ-5)
}
rootProject.name = "Kairn"
include(":app")
include(":benchmark")
