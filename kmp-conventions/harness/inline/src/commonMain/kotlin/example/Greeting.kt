package example

import androidx.compose.runtime.Composable

fun greeting(name: String): String = "Hello, $name"

// Exercises the Compose compiler plugin: this only compiles if the plugin version matches Kotlin.
@Composable
fun Label(text: String) {
    if (text.isEmpty()) return
}
