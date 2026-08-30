package dev.aarso.crashrecovery

import java.io.File

/**
 * Non-destructive "reset": move an app's on-disk state ASIDE instead of deleting it, so a
 * crash-loop caused by corrupt data can be broken by booting clean — while every byte stays
 * recoverable. This is the difference between "we set your work aside" and the heartbreak of
 * a bare wipe.
 *
 * A stash is a directory renamed within the same filesystem: instant and O(1) regardless of
 * size (gigabytes move as fast as kilobytes), atomic, and never a half-copy. Pure
 * `java.io.File` operations, so the move / restore / purge logic is unit-tested without
 * Android.
 */
internal object Quarantine {
    /** The per-file manifest of what a stash moved, so [restore] knows exactly what to put back. */
    const val MANIFEST = ".cr_manifest"

    /** Disposable dirs not worth stashing. `lib` is a symlink to native libs — never touch it. */
    val NEVER: Set<String> = setOf("cache", "code_cache", "lib")

    /**
     * Move every child of [dataDir] into `quarantineRoot/[stamp]/`, except: [keep] (the caller's
     * own bookkeeping), the [quarantineRoot] itself, and disposable caches (deleted, not kept;
     * `lib` is left untouched). Records a manifest. Returns the stamp dir when something was set
     * aside, or null when there was nothing to move (so the caller can avoid a pointless reset).
     */
    fun stash(
        dataDir: File,
        quarantineRoot: File,
        stamp: String,
        keep: Set<String> = emptySet(),
    ): File? {
        val children = dataDir.listFiles() ?: return null
        val dest = File(quarantineRoot, stamp)
        if (!dest.mkdirs() && !dest.isDirectory) return null
        var moved = 0
        val manifest = StringBuilder()
        for (child in children) {
            val name = child.name
            if (name == quarantineRoot.name || name in keep) continue
            if (name in NEVER) {
                if (name != "lib") child.deleteRecursively() // reclaim disposable caches
                continue
            }
            if (moveDir(child, File(dest, name))) {
                moved++
                manifest.append(name).append('\n')
            }
        }
        if (moved == 0) {
            dest.delete()
            // Don't leave an empty quarantine root behind (but keep it if prior stashes live there).
            if (quarantineRoot.list()?.isEmpty() == true) quarantineRoot.delete()
            return null
        }
        File(dest, MANIFEST).writeText(manifest.toString())
        return dest
    }

    /**
     * Move [stampDir]'s stashed entries back into [dataDir] (replacing any fresh empties created
     * since), then remove the stash. Returns true if everything listed was put back.
     */
    fun restore(stampDir: File, dataDir: File): Boolean {
        val names = runCatching { File(stampDir, MANIFEST).readLines() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
        if (names.isEmpty()) return false
        var ok = true
        for (name in names) {
            val from = File(stampDir, name)
            if (!from.exists()) continue
            val to = File(dataDir, name)
            if (to.exists()) to.deleteRecursively()
            if (!moveDir(from, to)) ok = false
        }
        purge(stampDir)
        return ok
    }

    /** Delete a whole quarantine tree (a stamp dir, or the root to clear every stash). */
    fun purge(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    /** The most-recently created stamp dir under [quarantineRoot], or null if there are none. */
    fun latest(quarantineRoot: File): File? =
        quarantineRoot.listFiles()
            ?.filter { it.isDirectory && File(it, MANIFEST).exists() }
            ?.maxByOrNull { it.name.toLongOrNull() ?: 0L }

    /** Total bytes held across every stash under [quarantineRoot] (for a human summary). */
    fun sizeBytes(quarantineRoot: File): Long =
        quarantineRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Move a directory, preferring an atomic rename; fall back to copy+delete across devices. */
    private fun moveDir(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        return runCatching {
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }.getOrDefault(false)
    }
}
