// Top-level build file. Plugins are declared here with `apply false` and applied
// per-module, so versions live in one place (gradle/libs.versions.toml). Mirrors
// Hyle-Design-System's AGP/Kotlin pins -- any module consumed via includeBuild must match
// its consumers' AGP version exactly (Personal-Tracker DECISIONS.md D-Q).
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
