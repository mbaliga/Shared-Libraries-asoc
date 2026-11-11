package dev.aarso.gradle.kmp

/** The two toolchain pin sets. Kept in step with catalog/asoc.versions.toml by PinsCatalogTest. */
object Pins {
    const val KOTLIN_CLASSIC = "2.1.20"
    const val COMPOSE_CLASSIC = "1.8.2"
    const val KOTLIN_IOS = "2.2.21"
    const val COMPOSE_IOS = "1.9.3"

    /** True when [kotlin] is at or above the iOS pin (the only versions allowed to declare ios()). */
    fun supportsIos(kotlin: String): Boolean = compare(kotlin, KOTLIN_IOS) >= 0

    /** Numeric dotted compare, ignoring any `-suffix` (2.2.21-RC counts as 2.2.21). */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
