package dev.aarso.platformports

/**
 * Every port in this library is a [PortComponent]. A component either really does its job on the
 * host it was built for, or it is a stub. A stub MUST say so: [isStub] is true and [stubNotice]
 * holds the sentence a UI shows the user. That one rule is what lets an app collect every stub
 * on a host in a single place ([PlatformPorts.stubNotices]) and print them, instead of each
 * feature quietly pretending.
 *
 * "Real" here means "implemented against the host API". It does not mean "run on a device": see
 * the README for what has and has not been exercised.
 */
interface PortComponent {
    /** Short stable name, e.g. "secret-store". Used as the heading of the notice. */
    val name: String

    /** True when this component does not do the real job on this host. */
    val isStub: Boolean

    /** Text for the UI when [isStub]; null when not a stub. Never blank for a stub. */
    val stubNotice: String?
}

/** What a UI shows for one stub: which part, and what is not happening. */
data class StubNotice(val component: String, val text: String) {
    /** Shape + word, never colour alone. */
    fun render(): String = "$STUB_GLYPH STUB ($component): $text"

    companion object {
        const val STUB_GLYPH: String = "△" // white up-pointing triangle
    }
}

/** Fallback wording used if a stub forgot to supply one; [violatesStubContract] flags it. */
const val MISSING_STUB_TEXT: String = "This part is a stub on this device and has not said what it does not do."

/** The notice for this component, or null if it is a real implementation. */
fun PortComponent.notice(): StubNotice? =
    if (isStub) StubNotice(name, stubNotice?.takeIf { it.isNotBlank() } ?: MISSING_STUB_TEXT) else null

/** True when the component breaks the contract: a stub without text, or a non-stub that has text. */
fun PortComponent.violatesStubContract(): Boolean =
    if (isStub) stubNotice.isNullOrBlank() else stubNotice != null

/** A one-line status for logs and settings pages. */
fun PortComponent.statusLine(): String = notice()?.render() ?: "● REAL ($name)"

/** A reading from a host probe: either a value, or an honest "this host cannot tell". */
sealed interface Reading<out T> {
    data class Known<T>(val value: T) : Reading<T>

    data class Unavailable(val reason: String) : Reading<Nothing>
}

/** The value if known, else null. */
fun <T> Reading<T>.valueOrNull(): T? = (this as? Reading.Known<T>)?.value
