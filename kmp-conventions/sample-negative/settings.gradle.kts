// NEGATIVE fixture: commonMain imports android.*; verifyNoAndroidImports MUST fail.
pluginManagement {
    includeBuild("..")
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
plugins { id("dev.aarso.kmp-conventions.settings") }
rootProject.name = "kmp-conventions-sample-negative"
