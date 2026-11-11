// Proof project: applies dev.aarso.kmp-conventions on JVM only (no Android SDK, no Mac needed).
pluginManagement {
    includeBuild("..")
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
plugins { id("dev.aarso.kmp-conventions.settings") }
rootProject.name = "kmp-conventions-sample"
// Skipped here: no ENABLE_ANDROID=1 and no local.properties. Nothing is declared at ":androidApp".
asoc.includeIfAndroid(":androidApp")
