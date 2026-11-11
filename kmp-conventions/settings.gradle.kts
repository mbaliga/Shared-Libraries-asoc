// kmp-conventions: an INCLUDED build (never `include(...)`d into the root build) that provides
// the plugin ids `dev.aarso.kmp-conventions` (project) and `dev.aarso.kmp-conventions.settings`
// (settings). Consumers pull it in with `pluginManagement { includeBuild("<path>/kmp-conventions") }`.
rootProject.name = "kmp-conventions"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
