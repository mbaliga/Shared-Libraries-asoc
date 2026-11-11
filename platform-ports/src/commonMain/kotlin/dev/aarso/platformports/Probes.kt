package dev.aarso.platformports

/** Battery and power-saver state. Any field is null when the host cannot say. */
data class PowerState(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val powerSaver: Boolean? = null,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) { "batteryPercent out of range: $batteryPercent" }
    }
}

interface PowerProbe : PortComponent {
    fun read(): Reading<PowerState>
}

/** Thermal pressure as a word and a shape, never a colour. */
enum class ThermalLevel(val word: String, val glyph: String) {
    NOMINAL("nominal", "○"),
    WARM("warm", "◐"),
    HOT("hot", "◕"),
    CRITICAL("critical", "●"),
}

data class ThermalState(val level: ThermalLevel, val celsius: Double?)

interface ThermalProbe : PortComponent {
    fun read(): Reading<ThermalState>
}

/**
 * Maps a temperature to a [ThermalLevel]. These cut-offs are OUR HEURISTIC for a phone- or
 * laptop-class SoC; they are not measured against a corpus (so a UI that shows the level should
 * say "our heuristic"). Hosts that report their own thermal status should use that instead.
 */
fun thermalLevelFor(celsius: Double): ThermalLevel = when {
    celsius < 60.0 -> ThermalLevel.NOMINAL
    celsius < 75.0 -> ThermalLevel.WARM
    celsius < 90.0 -> ThermalLevel.HOT
    else -> ThermalLevel.CRITICAL
}

enum class MeteredState(val word: String, val glyph: String) {
    METERED("metered", "▣"),
    UNMETERED("unmetered", "□"),
}

/** Whether the current connection is metered. A caller must treat Unavailable as "ask the user". */
interface NetworkProbe : PortComponent {
    fun read(): Reading<MeteredState>
}
