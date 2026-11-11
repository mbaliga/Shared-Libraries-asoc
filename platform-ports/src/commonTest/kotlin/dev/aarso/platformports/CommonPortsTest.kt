package dev.aarso.platformports

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StubContractTest {
    private class Fake(override val isStub: Boolean, override val stubNotice: String?) : PortComponent {
        override val name = "fake"
    }

    @Test fun realComponentHasNoNotice() {
        val c = Fake(false, null)
        assertNull(c.notice())
        assertFalse(c.violatesStubContract())
        assertTrue(c.statusLine().contains("REAL"))
    }

    @Test fun stubWithTextRendersShapeAndWord() {
        val c = Fake(true, "Does nothing.")
        val n = assertNotNull(c.notice())
        assertEquals("fake", n.component)
        assertTrue(n.render().startsWith(StubNotice.STUB_GLYPH))
        assertTrue(n.render().contains("STUB"))
        assertFalse(c.violatesStubContract())
    }

    @Test fun silentStubIsFlaggedAndStillGetsFallbackText() {
        val c = Fake(true, " ")
        assertTrue(c.violatesStubContract())
        assertEquals(MISSING_STUB_TEXT, c.notice()?.text)
    }

    @Test fun realComponentWithStubTextIsFlagged() {
        assertTrue(Fake(false, "oops").violatesStubContract())
    }

    @Test fun readingValueOrNull() {
        assertEquals(3, Reading.Known(3).valueOrNull())
        assertNull(Reading.Unavailable("no").valueOrNull())
    }
}

class KeyStorageTierTest {
    @Test fun ranksAreStrictlyOrderedStrongestFirst() {
        val ranks = KeyStorageTier.entries.map { it.rank }
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(ranks.size, ranks.toSet().size)
    }

    @Test fun fileTiersAndBelowNeedDisclosure() {
        assertFalse(KeyStorageTier.HARDWARE_BACKED.needsDisclosure)
        assertFalse(KeyStorageTier.OS_KEYCHAIN.needsDisclosure)
        assertTrue(KeyStorageTier.PASSPHRASE_FILE.needsDisclosure)
        assertTrue(KeyStorageTier.APP_PRIVATE_FILE.needsDisclosure)
        assertTrue(KeyStorageTier.MEMORY_ONLY.needsDisclosure)
        assertTrue(KeyStorageTier.UNAVAILABLE.needsDisclosure)
    }

    @Test fun everyTierHasShapeWordAndGuarantee() {
        for (t in KeyStorageTier.entries) {
            assertTrue(t.glyph.isNotBlank(), t.name)
            assertTrue(t.label.isNotBlank(), t.name)
            assertTrue(t.guarantee.isNotBlank(), t.name)
        }
        assertEquals(KeyStorageTier.entries.size, KeyStorageTier.entries.map { it.glyph }.toSet().size)
    }

    @Test fun weakerThan() {
        assertTrue(KeyStorageTier.APP_PRIVATE_FILE.isWeakerThan(KeyStorageTier.OS_KEYCHAIN))
        assertFalse(KeyStorageTier.OS_KEYCHAIN.isWeakerThan(KeyStorageTier.OS_KEYCHAIN))
    }

    @Test fun persistence() {
        assertTrue(KeyStorageTier.APP_PRIVATE_FILE.persists)
        assertFalse(KeyStorageTier.MEMORY_ONLY.persists)
    }
}

class SecretStoreTest {
    @Test fun inMemoryRoundTripAndCopies() {
        val s = InMemorySecretStore()
        val v = byteArrayOf(1, 2, 3)
        s.put("k", v)
        v[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), s.get("k"))
        assertEquals(setOf("k"), s.names())
        s.remove("k")
        assertNull(s.get("k"))
        s.remove("absent")
    }

    @Test fun invalidNamesRejected() {
        val s = InMemorySecretStore()
        assertFailsWith<SecretStoreException> { s.put(" ", byteArrayOf()) }
        assertFailsWith<SecretStoreException> { s.put("a\nb", byteArrayOf()) }
    }

    @Test fun reportForMemoryDisclosesWeakness() {
        val r = InMemorySecretStore().report()
        assertEquals(KeyStorageTier.MEMORY_ONLY, r.tier)
        assertFalse(r.isStub)
        assertTrue(r.lines.first().contains("memory only"))
        assertTrue(r.lines.any { it.startsWith("Weaker guarantee") })
    }

    @Test fun stubStoreRefusesAndReportsUnavailableWithNotice() {
        val s = Stubs.secretStore("No keys here.")
        assertEquals(KeyStorageTier.UNAVAILABLE, s.tier)
        assertTrue(s.isStub)
        assertFailsWith<SecretStoreException> { s.put("a", byteArrayOf(1)) }
        assertFailsWith<SecretStoreException> { s.get("a") }
        assertFailsWith<SecretStoreException> { s.remove("a") }
        assertFailsWith<SecretStoreException> { s.names() }
        assertTrue(s.report().lines.any { it.contains("No keys here.") })
    }
}

class AppDirectoriesTest {
    @Test fun linuxDefaults() {
        val d = resolveAppDirectories(HostOs.LINUX, "typewright", "/home/u", emptyMap())
        assertEquals("/home/u/.config/typewright", d.config)
        assertEquals("/home/u/.local/share/typewright", d.data)
        assertEquals("/home/u/.cache/typewright", d.cache)
        assertEquals("/home/u/.local/state/typewright/logs", d.logs)
    }

    @Test fun linuxHonoursAbsoluteXdgAndIgnoresRelative() {
        val env = mapOf("XDG_CONFIG_HOME" to "/x/cfg/", "XDG_DATA_HOME" to "relative/data")
        val d = resolveAppDirectories(HostOs.LINUX, "a", "/home/u", env)
        assertEquals("/x/cfg/a", d.config)
        assertEquals("/home/u/.local/share/a", d.data)
    }

    @Test fun macos() {
        val d = resolveAppDirectories(HostOs.MACOS, "a", "/Users/u", emptyMap())
        assertEquals("/Users/u/Library/Application Support/a", d.config)
        assertEquals(d.config, d.data)
        assertEquals("/Users/u/Library/Caches/a", d.cache)
        assertEquals("/Users/u/Library/Logs/a", d.logs)
    }

    @Test fun windowsUsesAppDataVariablesOrFallsBack() {
        val env = mapOf("APPDATA" to "C:\\Users\\u\\AppData\\Roaming", "LOCALAPPDATA" to "D:\\L")
        val d = resolveAppDirectories(HostOs.WINDOWS, "a", "C:\\Users\\u", env)
        assertEquals("C:\\Users\\u\\AppData\\Roaming\\a", d.config)
        assertEquals("D:\\L\\a\\data", d.data)
        assertEquals("D:\\L\\a\\cache", d.cache)
        assertEquals("D:\\L\\a\\logs", d.logs)
        val f = resolveAppDirectories(HostOs.WINDOWS, "a", "C:\\Users\\u", mapOf("APPDATA" to "rel"))
        assertEquals("C:\\Users\\u\\AppData\\Roaming\\a", f.config)
        assertEquals("C:\\Users\\u\\AppData\\Local\\a\\data", f.data)
    }

    @Test fun badAppIdsRejected() {
        for (bad in listOf("", " ", "..", ".", "a/b", "a\\b", "a:b", "a\nb")) {
            assertFailsWith<IllegalArgumentException>(bad) { resolveAppDirectories(HostOs.LINUX, bad, "/h", emptyMap()) }
        }
        assertFailsWith<IllegalArgumentException> { resolveAppDirectories(HostOs.LINUX, "a", " ", emptyMap()) }
    }
}

class FileAndShareTest {
    @Test fun extensionsNormalised() {
        val r = PickRequest("t", extensions = listOf(".TTF", "otf", " .otf ", "", "."))
        assertEquals(listOf("ttf", "otf"), r.normalizedExtensions)
    }

    @Test fun saveCannotBeMultiple() {
        assertFailsWith<IllegalArgumentException> { PickRequest("t", PickMode.SAVE, allowMultiple = true) }
    }

    @Test fun shareNeedsContentAndGoodMime() {
        assertFailsWith<IllegalArgumentException> { ShareRequest("t") }
        assertFailsWith<IllegalArgumentException> { ShareRequest("t", text = "x", mimeType = "text") }
        assertFailsWith<IllegalArgumentException> { ShareRequest("t", text = "x", mimeType = "a/b c") }
        ShareRequest("t", filePath = "/tmp/x.ufo.zip", mimeType = "application/zip")
    }

    @Test fun stubsSayUnavailable() {
        val p = Stubs.filePicker("no picker")
        assertEquals(PickOutcome.Unavailable("no picker"), p.pick(PickRequest("t")))
        val s = Stubs.shareSheet("no share")
        assertEquals(ShareOutcome.Unavailable("no share"), s.share(ShareRequest("t", text = "x")))
        assertTrue(p.isStub && s.isStub)
    }
}

class ProbesTest {
    @Test fun thermalBoundaries() {
        assertEquals(ThermalLevel.NOMINAL, thermalLevelFor(59.9))
        assertEquals(ThermalLevel.WARM, thermalLevelFor(60.0))
        assertEquals(ThermalLevel.HOT, thermalLevelFor(75.0))
        assertEquals(ThermalLevel.CRITICAL, thermalLevelFor(90.0))
    }

    @Test fun levelsHaveDistinctShapesAndWords() {
        assertEquals(4, ThermalLevel.entries.map { it.glyph }.toSet().size)
        assertEquals(4, ThermalLevel.entries.map { it.word }.toSet().size)
        assertNotNull(MeteredState.METERED.glyph)
        assertTrue(MeteredState.METERED.glyph != MeteredState.UNMETERED.glyph)
    }

    @Test fun powerStateRange() {
        PowerState(0, true)
        PowerState(null, null)
        assertFailsWith<IllegalArgumentException> { PowerState(101, true) }
        assertFailsWith<IllegalArgumentException> { PowerState(-1, true) }
    }

    @Test fun stubProbesReturnUnavailable() {
        assertEquals(Reading.Unavailable("r"), Stubs.powerProbe("r").read())
        assertEquals(Reading.Unavailable("r"), Stubs.thermalProbe("r").read())
        assertEquals(Reading.Unavailable("r"), Stubs.networkProbe("r").read())
    }
}

class BluetoothTest {
    @Test fun addressParsing() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.parseOrNull("aa:bb:cc:dd:ee:ff")?.value)
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.parseOrNull("AA-BB-CC-DD-EE-FF")?.value)
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddress.parseOrNull(" aabbccddeeff ")?.value)
        assertEquals(BluetoothAddress.parseOrNull("aabbccddeeff"), BluetoothAddress.parseOrNull("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun badAddressesRejected() {
        for (bad in listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:GG", "AA:BB-CC:DD:EE:FF", "AABBCCDDEEF", "AA:BB:CC:DD:EE:FF:00")) {
            assertNull(BluetoothAddress.parseOrNull(bad), bad)
        }
    }

    @Test fun uuids() {
        assertTrue(isValidServiceUuid(SPP_UUID))
        assertFalse(isValidServiceUuid("1101"))
        assertFalse(isValidServiceUuid("zzzzzzzz-0000-1000-8000-00805f9b34fb"))
    }

    @Test fun stubRfcomm() {
        val addr = assertNotNull(BluetoothAddress.parseOrNull("aabbccddeeff"))
        assertEquals(RfcommOutcome.Unavailable("r"), Stubs.rfcomm("r").connect(addr))
    }
}

class PlatformPortsTest {
    @Test fun allStubListsEveryComponentWithANotice() {
        val p = Stubs.allStub("nothing yet")
        assertEquals(8, p.components().size)
        assertEquals(8, p.stubNotices().size)
        assertEquals(8, p.stubNotices().map { it.component }.toSet().size)
        assertTrue(p.contractViolations().isEmpty())
        assertEquals(KeyStorageTier.UNAVAILABLE, p.keyStorageReport().tier)
        assertEquals(Reading.Unavailable("nothing yet"), p.appDirectories.resolve())
    }

    @Test fun realComponentsAreNotListed() {
        val base = Stubs.allStub("x")
        val p = PlatformPorts(
            base.appDirectories, InMemorySecretStore(), base.filePicker, base.shareSheet,
            base.power, base.thermal, base.network, base.rfcomm,
        )
        assertEquals(7, p.stubNotices().size)
        assertFalse(p.stubNotices().any { it.component == "secret-store" })
    }

    @Test fun defaultPortsHonourTheStubContractOnThisHost() {
        val p = defaultPlatformPorts("platform-ports-test")
        assertTrue(p.contractViolations().isEmpty(), p.contractViolations().toString())
    }
}
