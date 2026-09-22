package dev.aarso.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun tcpTransportRoundTripsAFramedPacket() {
        val received = CountDownLatch(1)
        val packet = AtomicReference<SessionPacket?>()
        val accepted = AtomicReference<LocalSessionTransport?>()
        val host = TcpLocalSessionHost.bind(
            listener = SessionPacketListener {
                packet.set(it)
                received.countDown()
            },
            onConnection = { accepted.set(it) },
        ).start()
        val port = host.port
        val client = TcpLocalSessionTransport.connect("127.0.0.1", port, SessionPacketListener { })
        client.send(SessionPacket(SessionId("tcp"), ParticipantId("peer"), SessionPacketType.EVENT, 1, "e1", "hello"))
        assertTrue(received.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(accepted.get() != null)
        client.close()
        host.close()
        assertEquals("hello", packet.get()?.payload)
    }
}
