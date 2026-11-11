package dev.aarso.gradle.kmp

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Fails when any resolved third-party component on the named configurations declares a licence
 * outside [LicencePolicy]. Reads each component's POM (and its parent chain) at execution time,
 * so it is not configuration-cache compatible. It checks what Maven metadata DECLARES; it is
 * not a legal review and cannot see licences a POM omits (those fail unless excepted).
 */
@DisableCachingByDefault(because = "resolves POMs at execution time")
abstract class LicenceCheck : DefaultTask() {
    /** Configuration names to audit, e.g. jvmRuntimeClasspath. Missing names are skipped. */
    @get:Input abstract val configurationNames: ListProperty<String>

    /** `group:name` coordinates accepted despite a missing/unknown POM licence. Each needs a written reason in the repo. */
    @get:Input abstract val exceptions: ListProperty<String>

    init {
        group = "verification"
        description = "Fails on any non-permissive dependency licence (permissive allowlist)."
        notCompatibleWithConfigurationCache("resolves POM files at execution time")
    }

    @TaskAction
    fun run() {
        val seen = linkedMapOf<String, List<String>>()
        for (name in configurationNames.get()) {
            val conf = project.configurations.findByName(name) ?: continue
            if (!conf.isCanBeResolved) continue
            collect(conf.incoming.resolutionResult.root, seen)
        }
        val bad = seen.filter { (coord, lic) ->
            coord.substringBeforeLast(':') !in exceptions.get() && !LicencePolicy.verdict(lic)
        }
        seen.forEach { (c, l) -> logger.lifecycle("licence  $c  ->  ${l.ifEmpty { listOf("(none declared)") }}") }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "Non-permissive or undeclared licence(s):\n" +
                    bad.entries.joinToString("\n") { "  ${it.key}: ${it.value.ifEmpty { listOf("(none declared)") }}" },
            )
        }
        logger.lifecycle("licence check: ${seen.size} component(s), all permissive.")
    }

    private fun collect(c: ResolvedComponentResult, out: MutableMap<String, List<String>>) {
        val id = c.id
        if (id is ModuleComponentIdentifier && id.toString() !in out) {
            out[id.toString()] = licencesOf(id.group, id.module, id.version)
        }
        c.dependencies.forEach { d ->
            val sel = (d as? org.gradle.api.artifacts.result.ResolvedDependencyResult)?.selected ?: return@forEach
            if (sel.id.toString() !in out) collect(sel, out)
        }
    }

    private fun pomOf(g: String, n: String, v: String): java.io.File? = try {
        val dep = project.dependencies.create("$g:$n:$v@pom")
        val conf: Configuration = project.configurations.detachedConfiguration(dep).apply { isTransitive = false }
        conf.resolve().firstOrNull()
    } catch (e: Exception) {
        null
    }

    private fun licencesOf(g: String, n: String, v: String, depth: Int = 0): List<String> {
        if (depth > 6) return emptyList()
        val pom = pomOf(g, n, v) ?: return emptyList()
        val dbf = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = dbf.newDocumentBuilder().parse(pom).documentElement
        fun child(e: Element, tag: String): Element? =
            (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().firstOrNull { it.tagName == tag }
        val lics = child(root, "licenses")?.let { ls ->
            (0 until ls.childNodes.length).map { ls.childNodes.item(it) }.filterIsInstance<Element>()
                .filter { it.tagName == "license" }
                .map { l -> listOfNotNull(child(l, "name")?.textContent?.trim(), child(l, "url")?.textContent?.trim()).joinToString(" ") }
        }.orEmpty()
        if (lics.isNotEmpty()) return lics
        val parent = child(root, "parent") ?: return emptyList()
        val pg = child(parent, "groupId")?.textContent?.trim() ?: return emptyList()
        val pn = child(parent, "artifactId")?.textContent?.trim() ?: return emptyList()
        val pv = child(parent, "version")?.textContent?.trim() ?: return emptyList()
        return licencesOf(pg, pn, pv, depth + 1)
    }
}
