plugins {
    alias(kmpPins.plugins.kotlin.multiplatform) apply false
    alias(kmpPins.plugins.kotlin.plugin.compose) apply false
    alias(kmpPins.plugins.compose.multiplatform) apply false
}

// Prints what actually resolved, so run.sh can compare it with the catalog instead of trusting it.
tasks.register("printPins") {
    val kotlinVersion = kmpPins.versions.kotlin.get()
    val composeVersion = kmpPins.versions.compose.multiplatform.get()
    doLast {
        println("PIN_CATALOG_KOTLIN=$kotlinVersion")
        println("PIN_CATALOG_COMPOSE=$composeVersion")
        println("PIN_RESOLVED_KOTLIN_PLUGIN=" + org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger))
    }
}
