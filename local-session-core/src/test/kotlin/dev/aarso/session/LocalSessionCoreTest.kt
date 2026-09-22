package dev.aarso.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionCoreTest {
    @Test
    fun duplicateAndStaleEventsAreIgnored() {
        val ledger = LocalSessionLedger(SessionId("match-1"), ParticipantId("host"), SessionRole.HOST)
        val peer = ParticipantId("peer")
        val packet = SessionPacket(SessionId("match-1"), peer, SessionPacketType.EVENT, 1, "e1", "score:1")

        assertTrue(ledger.accept(packet))
        assertFalse(ledger.accept(packet))
        assertFalse(ledger.accept(packet.copy(eventId = "e0", sequence = 1)))
        assertEquals(setOf("e1"), ledger.snapshot().appliedEventIds)
    }

    @Test
    fun localEventsAdvanceTheLocalSequence() {
        val ledger = LocalSessionLedger(SessionId("match-2"), ParticipantId("host"), SessionRole.HOST)
        ledger.localEvent(0, "e0", "ready")
        ledger.localEvent(1, "e1", "start")

        assertEquals(1L, ledger.snapshot().lastSequenceByParticipant[ParticipantId("host")])
        assertTrue(ledger.snapshot().connected)
    }
}
