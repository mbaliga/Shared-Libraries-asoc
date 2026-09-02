package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuarantineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dataDir(): File = tmp.newFolder("data")

    private fun writeFile(dir: File, rel: String, content: String) {
        val f = File(dir, rel)
        f.parentFile!!.mkdirs()
        f.writeText(content)
    }

    @Test
    fun `stash moves state aside, deletes caches, leaves lib, and writes a manifest`() {
        val data = dataDir()
        writeFile(data, "files/project.hyle", "precious")
        writeFile(data, "databases/app.db", "rows")
        writeFile(data, "shared_prefs/prefs.xml", "<map/>")
        writeFile(data, "cache/tmp.bin", "junk")
        File(data, "lib").mkdirs()

        val root = File(data, "cr_quarantine")
        val stamp = Quarantine.stash(data, root, "1000")

        assertTrue("something was stashed", stamp != null)
        // state moved aside
        assertFalse(File(data, "files").exists())
        assertFalse(File(data, "databases").exists())
        assertFalse(File(data, "shared_prefs").exists())
        assertEquals("precious", File(stamp, "files/project.hyle").readText())
        assertEquals("rows", File(stamp, "databases/app.db").readText())
        // disposable cache deleted, not stashed
        assertFalse(File(data, "cache").exists())
        assertFalse(File(stamp, "cache").exists())
        // lib (native libs) untouched
        assertTrue(File(data, "lib").exists())
        // manifest lists what moved
        val manifest = File(stamp, Quarantine.MANIFEST).readLines().filter { it.isNotBlank() }.toSet()
        assertEquals(setOf("files", "databases", "shared_prefs"), manifest)
    }

    @Test
    fun `stash never touches its own quarantine root or kept names`() {
        val data = dataDir()
        writeFile(data, "files/a.txt", "a")
        writeFile(data, "keepme/x", "x") // caller's bookkeeping to preserve in place
        writeFile(data, "cr_quarantine/old/leftover.txt", "old") // a prior stash
        val root = File(data, "cr_quarantine")

        val stamp = Quarantine.stash(data, root, "2000", keep = setOf("keepme"))!!

        // kept name stays put, not stashed
        assertTrue(File(data, "keepme/x").exists())
        assertFalse(File(stamp, "keepme").exists())
        // the prior quarantine dir is left intact, not nested into the new stamp
        assertTrue(File(data, "cr_quarantine/old/leftover.txt").exists())
        assertFalse(File(stamp, "cr_quarantine").exists())
    }

    @Test
    fun `restore puts every stashed entry back and removes the stash`() {
        val data = dataDir()
        writeFile(data, "files/project.hyle", "precious")
        writeFile(data, "databases/app.db", "rows")
        val root = File(data, "cr_quarantine")
        val stamp = Quarantine.stash(data, root, "3000")!!

        // app booted clean and wrote fresh (empty-ish) state
        writeFile(data, "files/fresh.txt", "new")

        val ok = Quarantine.restore(stamp, data)
        assertTrue(ok)
        // original data is back...
        assertEquals("precious", File(data, "files/project.hyle").readText())
        assertEquals("rows", File(data, "databases/app.db").readText())
        // ...replacing the fresh files dir entirely
        assertFalse(File(data, "files/fresh.txt").exists())
        // stash is gone
        assertFalse(stamp.exists())
    }

    @Test
    fun `latest picks the newest stamp and purge clears it`() {
        val data = dataDir()
        writeFile(data, "files/a", "a")
        val root = File(data, "cr_quarantine")
        Quarantine.stash(data, root, "1000")!!
        writeFile(data, "files/b", "b")
        val s2 = Quarantine.stash(data, root, "9000")!!

        assertEquals(s2, Quarantine.latest(root))
        Quarantine.purge(root)
        assertFalse(root.exists())
        assertNull(Quarantine.latest(File(data, "cr_quarantine")))
    }

    @Test
    fun `stash returns null when there is nothing worth moving`() {
        val data = dataDir()
        File(data, "cache").mkdirs() // only disposable content
        val root = File(data, "cr_quarantine")
        assertNull(Quarantine.stash(data, root, "1000"))
        assertFalse(root.exists()) // no empty stamp dir left behind
    }
}
