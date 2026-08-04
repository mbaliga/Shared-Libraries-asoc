// Top-level build file. Plugins are declared here with `apply false` and applied per-module,
// so versions live in one place (gradle/libs.versions.toml) — same convention as Hyle.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
