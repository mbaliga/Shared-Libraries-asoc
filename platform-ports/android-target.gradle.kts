// Applied by build.gradle.kts only when an Android SDK is present. Kept in its own script so a
// bare-JDK build (no SDK, AGP not on the classpath) never has to compile any Android DSL.
// NOT EXERCISED in the authoring container: there was no Android SDK to run it against.
apply(plugin = "com.android.library")

extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    androidTarget {
        publishLibraryVariants("release")
    }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.aarso.platformports"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
