package dev.aarso.gradle.kmp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConventionsTest {
    @Test fun importBanFlagsAndroidImport() {
        val v = AndroidImportScanner.violations("package a\nimport android.util.Log\nimport kotlin.io.println\n")
        assertEquals(listOf(2), v.map { it.first })
    }

    @Test fun importBanIgnoresAndroidxAndComments() {
        val src = "import androidx.annotation.Nullable\n// import android.os.Build\n/* import android.os.Build\n import android.x.Y */\nval s = \"android.os.Build\"\n"
        assertEquals(emptyList(), AndroidImportScanner.violations(src))
    }

    @Test fun importBanFlagsFullyQualifiedUse() {
        assertEquals(1, AndroidImportScanner.violations("val v = android.os.Build.VERSION.SDK_INT").size)
    }

    @Test fun licencePolicy() {
        assertTrue(LicencePolicy.isPermissive("The Apache Software License, Version 2.0 https://www.apache.org/licenses/LICENSE-2.0.txt"))
        assertTrue(LicencePolicy.isPermissive("MIT License"))
        assertTrue(LicencePolicy.isPermissive("BSD-3-Clause"))
        assertFalse(LicencePolicy.isPermissive("GNU General Public License v3"))
        assertFalse(LicencePolicy.isPermissive("GNU Lesser General Public License"))
        assertFalse(LicencePolicy.isPermissive("Eclipse Public License 1.0"))
        assertFalse(LicencePolicy.isPermissive("Mozilla Public License 2.0"))
        assertFalse(LicencePolicy.isPermissive("GPL with Classpath exception"))
        assertFalse(LicencePolicy.verdict(emptyList()))
        assertFalse(LicencePolicy.verdict(listOf("MIT", "GPL-2.0")))
    }

    @Test fun iosPinGate() {
        assertFalse(Pins.supportsIos("2.1.20"))
        assertTrue(Pins.supportsIos("2.2.21"))
        assertTrue(Pins.supportsIos("2.2.21-RC"))
        assertTrue(Pins.supportsIos("2.3.0"))
        assertFalse(Pins.supportsIos("2.2.20"))
    }

    @Test fun androidSdkSignal() {
        val d = kotlin.io.path.createTempDirectory().toFile()
        assertFalse(AndroidSdk.available(d, emptyMap()))
        assertTrue(AndroidSdk.available(d, mapOf("ENABLE_ANDROID" to "1")))
        assertFalse(AndroidSdk.available(d, mapOf("ANDROID_HOME" to "/x")))
        File(d, "local.properties").writeText("sdk.dir=/opt/sdk\n")
        assertTrue(AndroidSdk.available(d, emptyMap()))
    }

    @Test fun catalogMatchesPins() {
        val t = File(System.getProperty("asoc.catalog")).readText()
        fun v(k: String) = Regex("""^$k\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(t)!!.groupValues[1]
        assertEquals(Pins.KOTLIN_CLASSIC, v("kotlin-classic"))
        assertEquals(Pins.COMPOSE_CLASSIC, v("compose-classic"))
        assertEquals(Pins.KOTLIN_IOS, v("kotlin-ios"))
        assertEquals(Pins.COMPOSE_IOS, v("compose-ios"))
    }
}
