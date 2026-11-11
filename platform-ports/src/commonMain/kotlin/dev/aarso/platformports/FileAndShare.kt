package dev.aarso.platformports

enum class PickMode { OPEN, SAVE }

/**
 * Ask the user to choose file(s). [extensions] are lowercase, without dots; empty means any.
 * Always user-initiated: a port never opens a picker by itself.
 */
data class PickRequest(
    val title: String,
    val mode: PickMode = PickMode.OPEN,
    val extensions: List<String> = emptyList(),
    val allowMultiple: Boolean = false,
) {
    init {
        require(!(allowMultiple && mode == PickMode.SAVE)) { "A save dialog picks exactly one path." }
    }

    /** Extensions with leading dots stripped, lowercased, blanks and duplicates dropped. */
    val normalizedExtensions: List<String>
        get() = extensions.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.distinct()
}

sealed interface PickOutcome {
    data class Picked(val paths: List<String>) : PickOutcome

    data object Cancelled : PickOutcome

    data class Unavailable(val reason: String) : PickOutcome
}

interface FilePicker : PortComponent {
    fun pick(request: PickRequest): PickOutcome
}

/** What the user chose to hand to the system share sheet. Needs text, a file, or both. */
data class ShareRequest(
    val title: String,
    val text: String? = null,
    val filePath: String? = null,
    val mimeType: String = "text/plain",
) {
    init {
        require(!text.isNullOrEmpty() || !filePath.isNullOrBlank()) { "Nothing to share: give text or a file." }
        require(mimeType.count { it == '/' } == 1 && mimeType.none { it.isWhitespace() }) { "Bad MIME type: $mimeType" }
    }
}

sealed interface ShareOutcome {
    /** The request was handed to the host's sharing UI. It does not mean anything was sent. */
    data object Handed : ShareOutcome

    data class Unavailable(val reason: String) : ShareOutcome
}

/** The host's share sheet. The user picks the destination; this library transmits nothing itself. */
interface ShareSheet : PortComponent {
    fun share(request: ShareRequest): ShareOutcome
}
