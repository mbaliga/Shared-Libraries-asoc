package dev.aarso.gradle.kmp

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.CacheableTask
import java.io.File

/** Pure scanning logic, separated so it is unit-tested without Gradle. */
object AndroidImportScanner {
    private val importRe = Regex("""^\s*import\s+(static\s+)?`?android\.""")
    // android.<pkg>.<Class> used fully qualified, outside comments and string literals.
    private val fqRe = Regex("""(?<![\w.\"'])android\.[a-z]\w*\.[A-Z]""")

    /** Returns 1-based line numbers and text of violating lines in [text]. */
    fun violations(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        text.lines().forEachIndexed { i, raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
                inBlock = false
                line = line.substring(end + 2)
            }
            // strip block comments that open (and maybe close) on this line, and // comments
            while (true) {
                val s = line.indexOf("/*")
                if (s < 0) break
                val e = line.indexOf("*/", s + 2)
                if (e < 0) { line = line.substring(0, s); inBlock = true; break }
                line = line.substring(0, s) + " " + line.substring(e + 2)
            }
            line = line.replace(Regex("//.*$"), "")
            if (importRe.containsMatchIn(line) || fqRe.containsMatchIn(line)) out += (i + 1) to raw.trim()
        }
        return out
    }
}

/**
 * Fails when any `android.*` import (or fully-qualified `android.x.Y` reference) appears in a
 * non-Android source set: commonMain/commonTest/jvmMain/jvmTest and the other non-android sets.
 * Law: correctness-critical and shared code stays Android-free so it tests on a bare JDK.
 */
@CacheableTask
abstract class AndroidImportBan : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    init {
        group = "verification"
        description = "Fails on any android.* import in common/jvm (non-Android) source sets."
    }

    @TaskAction
    fun run() {
        val hits = mutableListOf<String>()
        var files = 0
        sources.asFileTree.matching { include("**/*.kt", "**/*.java", "**/*.kts") }.forEach { f: File ->
            files++
            AndroidImportScanner.violations(f.readText()).forEach { (n, l) ->
                hits += "${f.relativeTo(project.projectDir)}:$n: $l"
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("android.* is banned in common/jvm source sets (${hits.size} violation(s)):\n  " + hits.joinToString("\n  "))
        }
        logger.lifecycle("android import ban: $files file(s) scanned, 0 violations.")
    }
}
