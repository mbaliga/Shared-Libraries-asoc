package dev.aarso.interactionmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsInteractionModeStoreTest {

    @Test
    fun `a fresh store has no explicit choice and defaults to REGULAR`() {
        val store = PrefsInteractionModeStore(FakeSharedPreferences())

        assertFalse(store.hasExplicitChoice)
        assertEquals(InteractionMode.REGULAR, store.mode)
    }

    @Test
    fun `a fresh store with a legacy signal defaults to ASOC`() {
        val store = PrefsInteractionModeStore(FakeSharedPreferences(), legacySignal = true)

        assertFalse(store.hasExplicitChoice)
        assertEquals(InteractionMode.ASOC, store.mode)
    }

    @Test
    fun `setMode round-trips through the same store instance`() {
        val store = PrefsInteractionModeStore(FakeSharedPreferences())

        store.setMode(InteractionMode.ASOC)

        assertTrue(store.hasExplicitChoice)
        assertEquals(InteractionMode.ASOC, store.mode)
    }

    @Test
    fun `setMode round-trips through a second store over the same prefs`() {
        val prefs = FakeSharedPreferences()
        PrefsInteractionModeStore(prefs).setMode(InteractionMode.ASOC)

        val reopened = PrefsInteractionModeStore(prefs)

        assertTrue(reopened.hasExplicitChoice)
        assertEquals(InteractionMode.ASOC, reopened.mode)
    }

    @Test
    fun `an explicit REGULAR choice survives round-trip too (not just the non-default case)`() {
        val prefs = FakeSharedPreferences()
        PrefsInteractionModeStore(prefs).setMode(InteractionMode.REGULAR)

        val reopened = PrefsInteractionModeStore(prefs)

        assertTrue(reopened.hasExplicitChoice)
        assertEquals(InteractionMode.REGULAR, reopened.mode)
    }

    @Test
    fun `an explicit choice is never overridden by a legacy signal on a later open`() {
        val prefs = FakeSharedPreferences()
        PrefsInteractionModeStore(prefs).setMode(InteractionMode.REGULAR)

        // A later app version starts passing legacySignal = true; the user's prior explicit
        // REGULAR choice must still win.
        val reopened = PrefsInteractionModeStore(prefs, legacySignal = true)

        assertTrue(reopened.hasExplicitChoice)
        assertEquals(InteractionMode.REGULAR, reopened.mode)
    }

    @Test
    fun `setMode can flip an explicit choice back and forth`() {
        val store = PrefsInteractionModeStore(FakeSharedPreferences())

        store.setMode(InteractionMode.ASOC)
        assertEquals(InteractionMode.ASOC, store.mode)

        store.setMode(InteractionMode.REGULAR)
        assertEquals(InteractionMode.REGULAR, store.mode)
        assertTrue(store.hasExplicitChoice)
    }
}
