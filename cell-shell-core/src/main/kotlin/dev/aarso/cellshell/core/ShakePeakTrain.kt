package dev.aarso.cellshell.core

/**
 * The pure shake decision, separate from sensor plumbing: feed it gravity-normalised samples with
 * timestamps; it says when a genuine shake (a train of [required] peaks above [thresholdG] inside
 * [windowMs], peaks closer than [separationMs] collapsing into one) completed. [cooldownMs] of
 * refractory time follows a fire.
 */
class ShakePeakTrain(
    private val thresholdG: Float = SHAKE_THRESHOLD_G,
    private val required: Int = SHAKES_REQUIRED,
    private val windowMs: Long = SHAKE_WINDOW_MS,
    private val separationMs: Long = PEAK_SEPARATION_MS,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private val peakTimes = LongArray(required)
    private var peakCount = 0
    private var lastPeakAt = 0L
    private var firedAt = 0L

    /** Returns true exactly when this sample completes a shake. */
    fun onSample(gForce: Float, nowMillis: Long): Boolean {
        if (gForce < thresholdG) return false
        if (firedAt != 0L && nowMillis - firedAt < cooldownMs) return false
        if (lastPeakAt != 0L && nowMillis - lastPeakAt < separationMs) return false
        lastPeakAt = nowMillis
        val cutoff = nowMillis - windowMs
        var kept = 0
        for (i in 0 until peakCount) {
            if (peakTimes[i] >= cutoff) peakTimes[kept++] = peakTimes[i]
        }
        peakCount = kept
        if (peakCount < peakTimes.size) peakTimes[peakCount++] = nowMillis
        if (peakCount >= required) {
            peakCount = 0
            firedAt = nowMillis
            return true
        }
        return false
    }

    companion object {
        const val SHAKE_THRESHOLD_G = 2.4f
        const val SHAKES_REQUIRED = 3
        const val SHAKE_WINDOW_MS = 900L
        const val PEAK_SEPARATION_MS = 90L
        const val COOLDOWN_MS = 2_000L
    }
}
