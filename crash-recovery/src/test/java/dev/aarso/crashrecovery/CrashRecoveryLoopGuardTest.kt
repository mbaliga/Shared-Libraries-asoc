package dev.aarso.crashrecovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrashRecovery.giveUpOnRecovery] is the one remediation shared by every loop-breaker added
 * to close the crash-loop trap this module shipped:
 *  - the **onCreate-failure fallback** — `CrashRecoveryActivity.onCreate`'s own
 *    `buildRoot()`/`setContentView()` call throws, so the screen fails toward the real app
 *    instead of toward itself;
 *  - the **stale-entry-marker skip** — a prior launch set the durable "entered onCreate"
 *    marker and never cleared it, proof the recovery screen didn't even finish starting up
 *    last time;
 *  - the **N-attempt ceiling** — the SAME pending report has outlived
 *    [CrashReport.MAX_RECOVERY_ATTEMPTS] launches of `maybeShowRecovery()` without being
 *    resolved.
 *
 * All three resolve the same way: clear the report, clear the streak, clear the entry marker,
 * optionally relaunch, then run one final step. [CrashRecovery.giveUpOnRecovery] takes plain
 * function references rather than any Android type specifically so that sequencing — and, more
 * importantly, its tolerance of any one step throwing — is pinned here without a device or
 * Robolectric. A crash INSIDE this fix's own cleanup would turn the fix itself into a new
 * crash-loop surface, which is exactly the failure mode the rest of this module guards against
 * with `runCatching` on every write.
 */
class CrashRecoveryLoopGuardTest {

    @Test
    fun `runs every step in order, including an optional relaunch, when nothing throws`() {
        val calls = mutableListOf<String>()
        CrashRecovery.giveUpOnRecovery(
            clearReport = { calls += "clearReport" },
            clearStreak = { calls += "clearStreak" },
            clearEntryMarker = { calls += "clearEntryMarker" },
            relaunch = { calls += "relaunch" },
            then = { calls += "then" },
        )
        assertEquals(listOf("clearReport", "clearStreak", "clearEntryMarker", "relaunch", "then"), calls)
    }

    @Test
    fun `works with no relaunch step, exactly as the stale-marker and attempt-ceiling call sites use it`() {
        val calls = mutableListOf<String>()
        CrashRecovery.giveUpOnRecovery(
            clearReport = { calls += "clearReport" },
            clearStreak = { calls += "clearStreak" },
            clearEntryMarker = { calls += "clearEntryMarker" },
        )
        assertEquals(listOf("clearReport", "clearStreak", "clearEntryMarker"), calls)
    }

    @Test
    fun `a throwing step never stops the remaining steps from running`() {
        val calls = mutableListOf<String>()
        CrashRecovery.giveUpOnRecovery(
            clearReport = { throw IllegalStateException("disk full") },
            clearStreak = { calls += "clearStreak"; throw RuntimeException("boom") },
            clearEntryMarker = { calls += "clearEntryMarker" },
            relaunch = { calls += "relaunch"; throw RuntimeException("no launcher for package") },
            then = { calls += "then" },
        )
        // clearReport threw before recording itself; every step after it still ran, including
        // the final one -- a broken recovery screen must still reach the real app (or at least
        // call `finish()`) even if its own cleanup is having a bad day too.
        assertEquals(listOf("clearStreak", "clearEntryMarker", "relaunch", "then"), calls)
    }

    @Test
    fun `then always runs last, even when every other step throws`() {
        var thenRan = false
        CrashRecovery.giveUpOnRecovery(
            clearReport = { throw RuntimeException() },
            clearStreak = { throw RuntimeException() },
            clearEntryMarker = { throw RuntimeException() },
            relaunch = { throw RuntimeException() },
            then = { thenRan = true },
        )
        assertTrue(thenRan)
    }
}
