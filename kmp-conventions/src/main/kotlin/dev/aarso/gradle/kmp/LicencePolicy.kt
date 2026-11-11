package dev.aarso.gradle.kmp

/**
 * Permissive-only licence policy (directive I-11). Classification is by the licence name/url a
 * POM declares. Weak copyleft (MPL, EPL, LGPL) and strong copyleft (GPL, AGPL) are NOT permissive
 * here; an unknown or missing licence fails unless the coordinate is explicitly excepted.
 */
object LicencePolicy {
    private val permissive = listOf(
        Regex("apache", RegexOption.IGNORE_CASE),
        Regex("\\bmit\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbsd\\b", RegexOption.IGNORE_CASE),
        Regex("\\bisc\\b", RegexOption.IGNORE_CASE),
        Regex("\\bzlib\\b", RegexOption.IGNORE_CASE),
        Regex("unlicense|\\bcc0\\b|public domain", RegexOption.IGNORE_CASE),
        Regex("\\b0bsd\\b", RegexOption.IGNORE_CASE),
    )
    private val copyleft = listOf(
        Regex("gpl|gnu (lesser|affero|general)", RegexOption.IGNORE_CASE),
        Regex("\\bmpl\\b|mozilla", RegexOption.IGNORE_CASE),
        Regex("\\bepl\\b|eclipse public", RegexOption.IGNORE_CASE),
        Regex("\\bcddl\\b|\\bcpl\\b|common public", RegexOption.IGNORE_CASE),
    )

    /** True when a declared licence (name and/or url) is permissive and not also copyleft. */
    fun isPermissive(nameOrUrl: String): Boolean =
        copyleft.none { it.containsMatchIn(nameOrUrl) } && permissive.any { it.containsMatchIn(nameOrUrl) }

    /**
     * A component passes when it declares at least one licence and every declared licence is
     * permissive, OR (dual-licensing) at least one is permissive and none is copyleft-only... kept
     * strict: ALL declared must be permissive.
     */
    fun verdict(declared: List<String>): Boolean = declared.isNotEmpty() && declared.all(::isPermissive)
}
