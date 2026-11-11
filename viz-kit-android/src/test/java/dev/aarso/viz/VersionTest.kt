package dev.aarso.viz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VersionTest {
    @Test fun constantMatchesBuildFile() {
        val gradle = File("build.gradle.kts").readText()
        val declared = Regex("""val vizVersion = "([^"]+)"""").find(gradle)!!.groupValues[1]
        assertEquals(declared, Viz.VERSION)
        assertEquals("0.1.0", Viz.VERSION)
    }

    @Test fun portedFromTheSameContract() {
        assertEquals("0.2.0", Viz.WEB_VERSION)
        assertTrue(File("CONTRACT.md").readText().contains("viz.js"))
    }

    @Test fun textureOrderIsTheFixedOne() {
        assertEquals(listOf("solid", "hatch", "dots", "fade", "cross", "lines", "checks", "stipple"), Viz.ORDER.map { it.key })
    }
}
