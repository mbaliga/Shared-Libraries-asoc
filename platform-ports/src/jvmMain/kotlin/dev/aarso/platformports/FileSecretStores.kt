package dev.aarso.platformports

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Line format shared by both file stores: `hex(utf8 name) TAB base64(payload)`. */
private object SecretLines {
    fun hex(s: String): String = s.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun unhex(h: String): String {
        require(h.length % 2 == 0) { "odd hex length" }
        val b = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return String(b, StandardCharsets.UTF_8)
    }

    fun read(path: Path): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!Files.exists(path)) return out
        for (line in Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) throw SecretStoreException("Secret file is damaged: $path")
            out[line.substring(0, tab)] = line.substring(tab + 1)
        }
        return out
    }

    fun write(path: Path, lines: Map<String, String>) {
        try {
            val parent = path.toAbsolutePath().parent
            Files.createDirectories(parent)
            val tmp = Files.createTempFile(parent, path.fileName.toString(), ".tmp")
            try {
                restrict(tmp)
                Files.write(tmp, lines.map { "${it.key}\t${it.value}" }, StandardCharsets.UTF_8)
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        } catch (e: IOException) {
            throw SecretStoreException("Could not write the secret file.", e)
        }
    }

    /** Owner-only permissions where the filesystem supports POSIX; a no-op on Windows (ACLs). */
    private fun restrict(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Not POSIX: the folder's inherited ACL is all we have. The tier text already says so.
        }
    }
}

/**
 * Tier [KeyStorageTier.APP_PRIVATE_FILE]: values sit base64-encoded, NOT encrypted, in a file made
 * owner-read/write only (mode 0600 where POSIX). It is the weakest persistent tier and says so.
 */
class AppPrivateFileSecretStore(private val file: Path) : SecretStore {
    override val name = "secret-store"
    override val tier = KeyStorageTier.APP_PRIVATE_FILE
    override val backend = "owner-only file (not encrypted)"
    override val isStub = false
    override val stubNotice: String? = null

    private val lock = Any()

    override fun put(name: String, value: ByteArray) = synchronized(lock) {
        requireValidSecretName(name)
        val m = SecretLines.read(file)
        m[SecretLines.hex(name)] = Base64.getEncoder().encodeToString(value)
        SecretLines.write(file, m)
    }

    override fun get(name: String): ByteArray? = synchronized(lock) {
        SecretLines.read(file)[SecretLines.hex(name)]?.let { Base64.getDecoder().decode(it) }
    }

    override fun remove(name: String) = synchronized(lock) {
        val m = SecretLines.read(file)
        if (m.remove(SecretLines.hex(name)) != null) SecretLines.write(file, m)
    }

    override fun names(): Set<String> = synchronized(lock) {
        SecretLines.read(file).keys.map { SecretLines.unhex(it) }.toSet()
    }
}

/**
 * Tier [KeyStorageTier.PASSPHRASE_FILE]: each value is AES-256-GCM encrypted under a key derived
 * from [passphrase] with PBKDF2-HMAC-SHA256 and a per-file random salt. The secret name is bound as
 * associated data, so entries cannot be swapped between names. A wrong passphrase is detected
 * through a verifier entry and raises [SecretStoreException]; nothing is overwritten.
 *
 * @param iterations PBKDF2 cost. Default follows OWASP's 2023 figure for SHA-256; tests lower it.
 */
class PassphraseFileSecretStore(
    private val file: Path,
    passphrase: CharArray,
    private val iterations: Int = DEFAULT_ITERATIONS,
) : SecretStore {
    override val name = "secret-store"
    override val tier = KeyStorageTier.PASSPHRASE_FILE
    override val backend = "AES-256-GCM file, PBKDF2-HMAC-SHA256 x$iterations"
    override val isStub = false
    override val stubNotice: String? = null

    private val pass = passphrase.copyOf()
    private val lock = Any()
    private val random = SecureRandom()

    init {
        require(pass.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations >= 1) { "iterations must be positive" }
    }

    override fun put(name: String, value: ByteArray) = synchronized(lock) {
        requireValidSecretName(name)
        val (m, key) = open(createIfMissing = true)
        m[SecretLines.hex(name)] = seal(checkNotNull(key), name, value)
        SecretLines.write(file, m)
    }

    override fun get(name: String): ByteArray? = synchronized(lock) {
        val (m, key) = open(createIfMissing = false)
        m[SecretLines.hex(name)]?.let { unseal(key, name, it) }
    }

    override fun remove(name: String) = synchronized(lock) {
        val (m, key) = open(createIfMissing = false)
        if (key != null && m.remove(SecretLines.hex(name)) != null) SecretLines.write(file, m)
    }

    override fun names(): Set<String> = synchronized(lock) {
        val (m, _) = open(createIfMissing = false)
        m.keys.filter { it != SALT && it != VERIFY }.map { SecretLines.unhex(it) }.toSet()
    }

    /** Loads the file and checks the passphrase. Returns a null key only when the file has no entries yet. */
    private fun open(createIfMissing: Boolean): Pair<LinkedHashMap<String, String>, ByteArray?> {
        val m = SecretLines.read(file)
        if (m.isEmpty()) {
            if (!createIfMissing) return m to null
            val salt = ByteArray(16).also { random.nextBytes(it) }
            m[SALT] = Base64.getEncoder().encodeToString(salt)
            val key = derive(salt)
            m[VERIFY] = seal(key, VERIFY_NAME, VERIFY_PLAIN)
            return m to key
        }
        val salt = m[SALT]?.let { Base64.getDecoder().decode(it) }
            ?: throw SecretStoreException("Secret file has no salt; it is damaged.")
        val key = derive(salt)
        val verifier = m[VERIFY] ?: throw SecretStoreException("Secret file has no verifier; it is damaged.")
        val plain = try {
            unseal(key, VERIFY_NAME, verifier)
        } catch (e: SecretStoreException) {
            throw SecretStoreException("Wrong passphrase, or the secret file is damaged.", e)
        }
        if (!plain.contentEquals(VERIFY_PLAIN)) throw SecretStoreException("Wrong passphrase.")
        return m to key
    }

    private fun derive(salt: ByteArray): ByteArray = try {
        val spec = PBEKeySpec(pass, salt, iterations, 256)
        try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    } catch (e: GeneralSecurityException) {
        throw SecretStoreException("This JVM cannot derive keys with PBKDF2-HMAC-SHA256.", e)
    }

    private fun seal(key: ByteArray, name: String, value: ByteArray): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(value))
    }

    private fun unseal(key: ByteArray?, name: String, text: String): ByteArray {
        if (key == null) throw SecretStoreException("No key.")
        return try {
            val raw = Base64.getDecoder().decode(text)
            if (raw.size < 12 + 16) throw SecretStoreException("Entry too short.")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            c.updateAAD(name.toByteArray(StandardCharsets.UTF_8))
            c.doFinal(raw, 12, raw.size - 12)
        } catch (e: GeneralSecurityException) {
            throw SecretStoreException("Could not decrypt entry.", e)
        } catch (e: IllegalArgumentException) {
            throw SecretStoreException("Entry is not valid base64.", e)
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS: Int = 600_000
        private const val SALT = "SALT"
        private const val VERIFY = "VERIFY"
        private const val VERIFY_NAME = "\u0000verify"
        private val VERIFY_PLAIN = "platform-ports/verify/1".toByteArray(StandardCharsets.UTF_8)
    }
}
