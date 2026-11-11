package dev.aarso.gradle.kmp

import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import java.io.File
import java.lang.reflect.Method

/**
 * Reflective access to the Kotlin Gradle plugin's `kotlin { }` extension. Needed because this
 * plugin and KGP load in sibling classloaders (an included-build plugin cannot see the types of
 * a KGP the consumer applied), so compiled references to KGP classes fail at runtime with
 * NoClassDefFoundError. Only stable, long-lived member names are used.
 */
internal object KotlinAccess {
    fun extension(project: Project): Any = project.extensions.getByName("kotlin")

    private fun find(target: Any, name: String, params: Int): Method? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == params }

    fun call(target: Any, name: String): Any? {
        val m = find(target, name, 0) ?: error("kotlin extension has no $name() (KGP API changed?)")
        m.isAccessible = true
        return m.invoke(target)
    }

    /** Resolves the KGP version: plugin's own getter, then the jar file name it was loaded from. */
    fun version(project: Project): String {
        val plugin = project.plugins.getPlugin("org.jetbrains.kotlin.multiplatform")
        runCatching { call(plugin, "getPluginVersion") as String }.getOrNull()?.let { return it }
        val jar = plugin.javaClass.protectionDomain?.codeSource?.location?.path?.let { File(it).name }.orEmpty()
        Regex("""kotlin-gradle-plugin-(\d+\.\d+\.\d+[\w.-]*?)(-gradle\d+)?\.jar""").find(jar)?.let { return it.groupValues[1] }
        error("cannot determine the Kotlin Gradle plugin version")
    }

    /** (name, kotlin srcDirs + src/<name>/java) of every KotlinSourceSet. */
    fun sourceSetDirs(project: Project): List<Pair<String, Set<File>>> {
        val sets = call(extension(project), "getSourceSets") as Iterable<*>
        return sets.filterNotNull().map { ss ->
            val name = call(ss, "getName") as String
            val kotlin = call(ss, "getKotlin") as SourceDirectorySet
            name to (kotlin.srcDirs + project.file("src/$name/java"))
        }
    }
}
