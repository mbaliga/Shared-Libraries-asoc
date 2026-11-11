package dev.aarso.platformports

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun tmp(): Path = Files.createTempDirectory("platform-ports-test")

class AppPrivateFileSecretStoreTest {
    @Test fun roundTripAndPersistsAcrossInstances() {
        val f = tmp().resolve("s/secrets.v1")
        val a = AppPrivateFileSecretStore(f)
        a.put("openai", "sk-1".toByteArray())
        a.put("名前", byteArrayOf(0, 1, 2))
        val b = AppPrivateFileSecretStore(f)
        assertContentEquals("sk-1".toByteArray(), b.get("openai"))
        assertContentEquals(byteArrayOf(0, 1, 2), b.get("名前"))
        assertEquals(setOf("openai", "名前"), b.names())
        b.remove("openai")
        assertNull(AppPrivateFileSecretStore(f).get("openai"))
        assertEquals(KeyStorageTier.APP_PRIVATE_FILE, b.tier)
        assertTrue(b.report().lines.any { it.startsWith("Weaker guarantee") })
    }

    @Test fun missingFileMeansEmpty() {
        val s = AppPrivateFileSecretStore(tmp().resolve("none"))
        assertNull(s.get("a"))
        assertTrue(s.names().isEmpty())
        s.remove("a")
    }

    @Test fun fileIsOwnerOnlyOnPosix() {
        val f = tmp().resolve("secrets.v1")
        AppPrivateFileSecretStore(f).put("a", byteArrayOf(1))
        val perms = try {
            Files.getPosixFilePermissions(f)
        } catch (_: UnsupportedOperationException) {
            return
        }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test fun damagedFileIsReportedNotIgnored() {
        val f = tmp().resolve("secrets.v1")
        f.writeText("no-tab-here\n")
        assertFailsWith<SecretStoreException> { AppPrivateFileSecretStore(f).names() }
    }

    @Test fun blankNameRejected() {
        assertFailsWith<SecretStoreException> { AppPrivateFileSecretStore(tmp().resolve("x")).put("", byteArrayOf()) }
    }
}

class PassphraseFileSecretStoreTest {
    private fun store(f: Path, pass: String = "correct horse") =
        PassphraseFileSecretStore(f, pass.toCharArray(), iterations = 1000)

    @Test fun roundTripAcrossInstances() {
        val f = tmp().resolve("secrets.enc")
        store(f).put("k", "v".toByteArray())
        val b = store(f)
        assertContentEquals("v".toByteArray(), b.get("k"))
        assertEquals(setOf("k"), b.names())
        assertEquals(KeyStorageTier.PASSPHRASE_FILE, b.tier)
        b.remove("k")
        assertNull(store(f).get("k"))
    }

    @Test fun fileDoesNotContainThePlaintext() {
        val f = tmp().resolve("secrets.enc")
        store(f).put("k", "SUPERSECRETVALUE".toByteArray())
        assertFalse(Files.readString(f).contains("SUPERSECRETVALUE"))
        assertFalse(Files.readString(f).contains(java.util.Base64.getEncoder().encodeToString("SUPERSECRETVALUE".toByteArray())))
    }

    @Test fun wrongPassphraseRefusesToReadOrWriteAndLeavesFileIntact() {
        val f = tmp().resolve("secrets.enc")
        store(f).put("k", "v".toByteArray())
        val before = Files.readAllBytes(f)
        val bad = store(f, "wrong")
        assertFailsWith<SecretStoreException> { bad.get("k") }
        assertFailsWith<SecretStoreException> { bad.put("k2", byteArrayOf(1)) }
        assertFailsWith<SecretStoreException> { bad.names() }
        assertContentEquals(before, Files.readAllBytes(f))
    }

    @Test fun entriesCannotBeSwappedBetweenNames() {
        val f = tmp().resolve("secrets.enc")
        val s = store(f)
        s.put("a", "AAA".toByteArray())
        s.put("b", "BBB".toByteArray())
        fun hex(n: String) = n.toByteArray().joinToString("") { "%02x".format(it) }
        val lines = Files.readAllLines(f).associate { it.substringBefore('\t') to it.substringAfter('\t') }.toMutableMap()
        val a = hex("a")
        val b = hex("b")
        lines[a] = lines[b]!!
        Files.write(f, lines.map { "${it.key}\t${it.value}" })
        assertFailsWith<SecretStoreException> { store(f).get("a") }
    }

    @Test fun tamperedEntryDetected() {
        val f = tmp().resolve("secrets.enc")
        store(f).put("a", "AAA".toByteArray())
        val hexA = "61"
        val text = Files.readAllLines(f).map { line ->
            if (line.startsWith("$hexA\t")) {
                val raw = java.util.Base64.getDecoder().decode(line.substringAfter('\t'))
                raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
                "$hexA\t" + java.util.Base64.getEncoder().encodeToString(raw)
            } else {
                line
            }
        }
        Files.write(f, text)
        assertFailsWith<SecretStoreException> { store(f).get("a") }
    }

    @Test fun emptyPassphraseAndBadIterationsRejected() {
        assertFailsWith<IllegalArgumentException> { PassphraseFileSecretStore(tmp().resolve("x"), CharArray(0)) }
        assertFailsWith<IllegalArgumentException> { PassphraseFileSecretStore(tmp().resolve("x"), "p".toCharArray(), 0) }
    }

    @Test fun backendNamesTheKdf() {
        assertTrue(store(tmp().resolve("x")).backend.contains("PBKDF2"))
    }
}

class JvmHostTest {
    @Test fun hostOsFromNames() {
        assertEquals(HostOs.LINUX, hostOsFrom("Linux"))
        assertEquals(HostOs.WINDOWS, hostOsFrom("Windows 11"))
        assertEquals(HostOs.MACOS, hostOsFrom("Mac OS X"))
        assertEquals(HostOs.MACOS, hostOsFrom("Darwin"))
        assertEquals(HostOs.OTHER, hostOsFrom("Plan 9"))
    }

    @Test fun jvmAppDirectoriesUsesInjectedHost() {
        val d = JvmAppDirectories("a", "Linux", "/home/u", emptyMap()).resolve()
        assertEquals("/home/u/.config/a", assertIs<Reading.Known<AppDirectories>>(d).value.config)
        assertIs<Reading.Unavailable>(JvmAppDirectories("a", "Linux", "", emptyMap()).resolve())
    }

    @Test fun osKeychainStubIsVisibleAndSays() {
        val s = JvmSecretStores.osKeychainStub()
        assertTrue(s.isStub)
        assertEquals(JvmSecretStores.OS_KEYCHAIN_NOTICE, s.stubNotice)
    }

    @Test fun storeFactoriesUseTheNamedTiers() {
        val d = tmp().toString()
        assertEquals(KeyStorageTier.APP_PRIVATE_FILE, JvmSecretStores.appPrivateFile(d).tier)
        assertEquals(KeyStorageTier.PASSPHRASE_FILE, JvmSecretStores.passphraseFile(d, "p".toCharArray()).tier)
    }

    @Test fun defaultPortsOnJvmNameTheirStubs() {
        val p = defaultPlatformPorts("platform-ports-test")
        assertTrue(p.contractViolations().isEmpty())
        val stubbed = p.stubNotices().map { it.component }.toSet()
        assertEquals(setOf("share-sheet", "metered-network-probe", "bluetooth-rfcomm"), stubbed)
        assertEquals(KeyStorageTier.APP_PRIVATE_FILE, p.keyStorageReport().tier)
    }
}

class LinuxSysfsProbeTest {
    private fun supply(root: Path, dir: String, vararg kv: Pair<String, String>) {
        val d = root.resolve(dir).createDirectories()
        for ((k, v) in kv) d.resolve(k).writeText(v + "\n")
    }

    @Test fun batteryReadAndMainsIgnored() {
        val root = tmp()
        supply(root, "AC", "type" to "Mains")
        supply(root, "BAT0", "type" to "Battery", "capacity" to "73", "status" to "Discharging")
        val r = assertIs<Reading.Known<PowerState>>(LinuxSysfsPowerProbe(root).read())
        assertEquals(PowerState(73, false), r.value)
    }

    @Test fun chargingAndUnknownStatus() {
        val root = tmp()
        supply(root, "BAT0", "type" to "Battery", "capacity" to "5", "status" to "Charging")
        assertEquals(true, LinuxSysfsPowerProbe(root).read().valueOrNull()?.charging)
        val root2 = tmp()
        supply(root2, "BAT0", "type" to "Battery", "capacity" to "500", "status" to "Weird")
        val s = LinuxSysfsPowerProbe(root2).read().valueOrNull()
        assertNull(s?.batteryPercent)
        assertNull(s?.charging)
    }

    @Test fun noBatteryOrNoSysfsIsUnavailable() {
        val root = tmp()
        supply(root, "AC", "type" to "Mains")
        assertIs<Reading.Unavailable>(LinuxSysfsPowerProbe(root).read())
        assertIs<Reading.Unavailable>(LinuxSysfsPowerProbe(root.resolve("missing")).read())
    }

    @Test fun thermalReportsHottestZone() {
        val root = tmp()
        supply(root, "thermal_zone0", "temp" to "45000")
        supply(root, "thermal_zone1", "temp" to "78500")
        supply(root, "cooling_device0", "temp" to "99999999")
        val s = assertIs<Reading.Known<ThermalState>>(LinuxSysfsThermalProbe(root).read()).value
        assertEquals(78.5, s.celsius)
        assertEquals(ThermalLevel.HOT, s.level)
    }

    @Test fun thermalIgnoresGarbageAndMissing() {
        val root = tmp()
        supply(root, "thermal_zone0", "temp" to "notanumber")
        supply(root, "thermal_zone1", "temp" to "-300000")
        assertIs<Reading.Unavailable>(LinuxSysfsThermalProbe(root).read())
        assertIs<Reading.Unavailable>(LinuxSysfsThermalProbe(root.resolve("missing")).read())
    }
}

class SwingPickerHeadlessTest {
    @Test fun headlessReturnsUnavailableNotACrash() {
        System.setProperty("java.awt.headless", "true")
        val out = SwingFilePicker().pick(PickRequest("t"))
        assertIs<PickOutcome.Unavailable>(out)
    }
}
