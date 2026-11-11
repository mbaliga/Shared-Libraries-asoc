package dev.aarso.platformports

/**
 * Where a secret actually lives, strongest first. The tier is REPORTED to the UI (OQ-22): an app
 * shows [label], [glyph] and [guarantee] next to wherever the user enters a key, and shows the
 * weaker guarantee in words when [needsDisclosure].
 *
 * The glyph is a shape and the label is a word; colour is never the signal.
 */
enum class KeyStorageTier(
    val rank: Int,
    val label: String,
    val glyph: String,
    val persists: Boolean,
    val guarantee: String,
) {
    HARDWARE_BACKED(
        4,
        "hardware-backed",
        "◆◆◆",
        true,
        "The key material is held by dedicated hardware (secure element, TEE or TPM) and cannot be read out.",
    ),
    OS_KEYCHAIN(
        3,
        "OS keychain",
        "◆◆",
        true,
        "Held by the operating system's credential store (Keychain, Credential Manager, Secret Service) " +
            "and protected by the user's login.",
    ),
    PASSPHRASE_FILE(
        2,
        "passphrase-protected file",
        "◆",
        true,
        "Encrypted in a file with a key derived from a passphrase. Only as strong as that passphrase, " +
            "and anything running as you can attempt to guess it offline.",
    ),
    APP_PRIVATE_FILE(
        1,
        "app-private file",
        "□",
        true,
        "Stored unencrypted in a file only this app's account should read. Anything running as you, " +
            "and any backup of the folder, can read it.",
    ),
    MEMORY_ONLY(
        0,
        "memory only",
        "○",
        false,
        "Kept in memory and lost when the app closes. Nothing is written to disk.",
    ),
    UNAVAILABLE(
        -1,
        "no storage",
        "✕",
        false,
        "This host has no secret storage here. Keys cannot be saved.",
    ),
    ;

    /** True when the weaker guarantee must be shown to the user in words (file tiers and below). */
    val needsDisclosure: Boolean get() = rank <= PASSPHRASE_FILE.rank

    fun isWeakerThan(other: KeyStorageTier): Boolean = rank < other.rank
}

/** Raised when a store cannot do what was asked (wrong passphrase, unwritable folder, stub). */
class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Secret storage that knows and tells its [tier]. Values are bytes; names are non-blank text.
 * Implementations never log values and never send them anywhere.
 */
interface SecretStore : PortComponent {
    val tier: KeyStorageTier

    /** Human description of the concrete backend, e.g. "AES-GCM file, PBKDF2-SHA256". */
    val backend: String

    @Throws(SecretStoreException::class)
    fun put(name: String, value: ByteArray)

    @Throws(SecretStoreException::class)
    fun get(name: String): ByteArray?

    @Throws(SecretStoreException::class)
    fun remove(name: String)

    @Throws(SecretStoreException::class)
    fun names(): Set<String>
}

/** The `keyStorage` report a UI renders, built from a store. */
data class KeyStorageReport(
    val tier: KeyStorageTier,
    val backend: String,
    val isStub: Boolean,
    val lines: List<String>,
)

/** Builds the report. The first line is the headline; later lines are the disclosure. */
fun SecretStore.report(): KeyStorageReport {
    val lines = mutableListOf("${tier.glyph} ${tier.label} — $backend")
    lines += tier.guarantee
    if (tier.needsDisclosure) lines += "Weaker guarantee: shown because this is not an OS keychain or hardware."
    notice()?.let { lines += it.render() }
    return KeyStorageReport(tier, backend, isStub, lines)
}

/** Rejects names a store cannot represent. Shared so every backend refuses the same inputs. */
fun requireValidSecretName(name: String) {
    if (name.isBlank()) throw SecretStoreException("A secret needs a non-blank name.")
    if (name.any { it.isISOControl() }) throw SecretStoreException("A secret name cannot contain control characters.")
}

/** Volatile store: honest about writing nothing to disk. Used by tests and by apps that choose it. */
class InMemorySecretStore : SecretStore {
    private val map = LinkedHashMap<String, ByteArray>()
    override val name: String = "secret-store"
    override val tier: KeyStorageTier = KeyStorageTier.MEMORY_ONLY
    override val backend: String = "in-memory map"
    override val isStub: Boolean = false
    override val stubNotice: String? = null

    override fun put(name: String, value: ByteArray) {
        requireValidSecretName(name)
        map[name] = value.copyOf()
    }

    override fun get(name: String): ByteArray? = map[name]?.copyOf()

    override fun remove(name: String) {
        map.remove(name)
    }

    override fun names(): Set<String> = map.keys.toSet()
}
