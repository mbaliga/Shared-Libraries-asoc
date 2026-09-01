package dev.aarso.localsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSessionEngineTest {
    private val sessionId = SessionId("session-1")
    private val hostId = ParticipantId("host-1")
    private var clock = 1_000L

    private fun engine(
        maxParticipants: Int = 8,
        recentEventLimit: Int = 128,
        processedMessageLimit: Int = 1_024,
        maxPayloadBytes: Int = 64 * 1_024,
    ) = LocalSessionEngine.create(
        sessionId = sessionId,
        hostId = hostId,
        hostDisplayName = "Coach",
        maxParticipants = maxParticipants,
        recentEventLimit = recentEventLimit,
        processedMessageLimit = processedMessageLimit,
        maxPayloadBytes = maxPayloadBytes,
        nowEpochMillis = { clock++ },
    )

    private fun join(
        engine: LocalSessionEngine,
        id: String = "player-1",
        message: String = "join-1",
        roles: Set<ParticipantRole> = setOf(ParticipantRole.PLAYER),
    ): SessionResult = engine.apply(
        JoinSession(
            sessionId,
            MessageId(message),
            ParticipantCandidate(ParticipantId(id), "Player", roles),
        ),
    )

    @Test
    fun `new room starts with one host in the lobby`() {
        val snapshot = engine().snapshot()
        assertEquals(LocalSessionEngine.PROTOCOL_VERSION, snapshot.protocolVersion)
        assertEquals(SessionPhase.LOBBY, snapshot.phase)
        assertEquals(hostId, snapshot.hostId)
        assertEquals(setOf(ParticipantRole.HOST, ParticipantRole.COACH), snapshot.participants.single().roles)
        assertEquals(0L, snapshot.revision)
    }

    @Test
    fun `players join and update only their own ready state`() {
        val engine = engine()
        assertTrue(join(engine) is SessionResult.Accepted)
        val ready = engine.apply(SetParticipantReady(sessionId, MessageId("ready-1"), ParticipantId("player-1"), true))
        assertTrue(ready is SessionResult.Accepted)
        assertTrue(ready.snapshot.participants.single { it.id.value == "player-1" }.ready)
        assertFalse(ready.snapshot.participants.single { it.id == hostId }.ready)
    }

    @Test
    fun `host role cannot be claimed and participant capacity is enforced`() {
        val engine = engine(maxParticipants = 2)
        val reserved = join(engine, roles = setOf(ParticipantRole.HOST), message = "bad-host") as SessionResult.Rejected
        assertEquals(RejectionCode.HOST_ROLE_RESERVED, reserved.code)
        assertTrue(join(engine, message = "join-good") is SessionResult.Accepted)
        val full = join(engine, id = "player-2", message = "join-full") as SessionResult.Rejected
        assertEquals(RejectionCode.SESSION_FULL, full.code)
    }

    @Test
    fun `only host controls valid lifecycle transitions`() {
        val engine = engine()
        join(engine)
        val playerStart = engine.apply(
            ChangeSessionPhase(sessionId, MessageId("start-player"), ParticipantId("player-1"), SessionPhase.ACTIVE),
        ) as SessionResult.Rejected
        assertEquals(RejectionCode.HOST_ONLY, playerStart.code)

        assertTrue(
            engine.apply(ChangeSessionPhase(sessionId, MessageId("start"), hostId, SessionPhase.ACTIVE))
                is SessionResult.Accepted,
        )
        val invalid = engine.apply(
            ChangeSessionPhase(sessionId, MessageId("lobby-again"), hostId, SessionPhase.LOBBY),
        ) as SessionResult.Rejected
        assertEquals(RejectionCode.INVALID_PHASE_TRANSITION, invalid.code)
    }

    @Test
    fun `events are attributed ordered and can target known participants`() {
        val engine = engine()
        join(engine)
        val first = engine.apply(
            PublishSessionEvent(
                sessionId,
                MessageId("event-1"),
                ParticipantId("player-1"),
                topic = "archery/shot",
                payload = "{\"score\":10}",
                recipientIds = setOf(hostId),
            ),
        ) as SessionResult.Accepted
        val second = engine.apply(
            PublishSessionEvent(sessionId, MessageId("event-2"), hostId, "coach/cue", "{\"cue\":\"expand\"}"),
        ) as SessionResult.Accepted

        assertEquals(1L, first.snapshot.recentEvents.single().sequence)
        assertEquals(2L, second.snapshot.recentEvents.last().sequence)
        assertEquals(ParticipantId("player-1"), first.snapshot.recentEvents.single().senderId)
        assertEquals(setOf(hostId), first.snapshot.recentEvents.single().recipientIds)
    }

    @Test
    fun `duplicate accepted message never repeats its transition`() {
        val engine = engine()
        val command = JoinSession(
            sessionId,
            MessageId("join-once"),
            ParticipantCandidate(ParticipantId("player-1"), "Player", setOf(ParticipantRole.PLAYER)),
        )
        val first = engine.apply(command) as SessionResult.Accepted
        val duplicate = engine.apply(command) as SessionResult.Accepted
        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertTrue(duplicate.changes.isEmpty())
        assertEquals(2, duplicate.snapshot.participants.size)
        assertEquals(first.snapshot.revision, duplicate.snapshot.revision)
    }

    @Test
    fun `unknown recipients and oversized payloads are rejected`() {
        val engine = engine(maxPayloadBytes = 5)
        val unknown = engine.apply(
            PublishSessionEvent(
                sessionId,
                MessageId("unknown-target"),
                hostId,
                "coach/cue",
                "ok",
                recipientIds = setOf(ParticipantId("missing")),
            ),
        ) as SessionResult.Rejected
        assertEquals(RejectionCode.UNKNOWN_RECIPIENT, unknown.code)

        val large = engine.apply(
            PublishSessionEvent(sessionId, MessageId("too-large"), hostId, "coach/cue", "123456"),
        ) as SessionResult.Rejected
        assertEquals(RejectionCode.PAYLOAD_TOO_LARGE, large.code)
    }

    @Test
    fun `history and processed message windows stay bounded`() {
        val engine = engine(recentEventLimit = 2, processedMessageLimit = 2)
        repeat(3) { index ->
            engine.apply(
                PublishSessionEvent(sessionId, MessageId("event-$index"), hostId, "test/event", "$index"),
            )
        }
        assertEquals(listOf(2L, 3L), engine.snapshot().recentEvents.map { it.sequence })

        val firstAgain = engine.apply(
            PublishSessionEvent(sessionId, MessageId("event-0"), hostId, "test/event", "reused-after-window"),
        ) as SessionResult.Accepted
        assertFalse(firstAgain.duplicate)
        assertEquals(4L, firstAgain.snapshot.recentEvents.last().sequence)
    }

    @Test
    fun `closed session rejects new work while accepted retries remain idempotent`() {
        val engine = engine()
        val close = ChangeSessionPhase(sessionId, MessageId("close"), hostId, SessionPhase.CLOSED)
        assertTrue(engine.apply(close) is SessionResult.Accepted)
        assertTrue((engine.apply(close) as SessionResult.Accepted).duplicate)
        val after = engine.apply(ParticipantHeartbeat(sessionId, MessageId("after"), hostId)) as SessionResult.Rejected
        assertEquals(RejectionCode.SESSION_CLOSED, after.code)
    }

    @Test
    fun `coordinator notifies once and supports unsubscribe`() {
        val coordinator = LocalSessionCoordinator(engine())
        var notifications = 0
        val subscription = coordinator.subscribe { notifications += it.changes.size }
        coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("beat-1"), hostId))
        coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("beat-1"), hostId))
        subscription.close()
        coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("beat-2"), hostId))
        assertEquals(1, notifications)
    }

    @Test
    fun `one broken observer cannot prevent other participants receiving a transition`() {
        var failures = 0
        var deliveries = 0
        val coordinator = LocalSessionCoordinator(engine()) { failures += 1 }
        coordinator.subscribe { error("observer failed") }
        coordinator.subscribe { deliveries += it.changes.size }

        val result = coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("beat-safe"), hostId))

        assertTrue(result is SessionResult.Accepted)
        assertEquals(1, failures)
        assertEquals(1, deliveries)
    }

    @Test
    fun `observer reentry is queued so every listener sees canonical revision order`() {
        val coordinator = LocalSessionCoordinator(engine())
        val observedRevisions = mutableListOf<Long>()
        coordinator.subscribe { accepted ->
            if (accepted.snapshot.revision == 1L) {
                coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("nested-beat"), hostId))
            }
        }
        coordinator.subscribe { observedRevisions += it.snapshot.revision }

        coordinator.dispatch(ParticipantHeartbeat(sessionId, MessageId("outer-beat"), hostId))

        assertEquals(listOf(1L, 2L), observedRevisions)
    }

    @Test
    fun `wrong session and host departure are explicit rejections`() {
        val engine = engine()
        val wrong = engine.apply(
            ParticipantHeartbeat(SessionId("another-session"), MessageId("wrong"), hostId),
        ) as SessionResult.Rejected
        assertEquals(RejectionCode.WRONG_SESSION, wrong.code)
        val hostLeave = engine.apply(LeaveSession(sessionId, MessageId("host-leave"), hostId)) as SessionResult.Rejected
        assertEquals(RejectionCode.HOST_CANNOT_LEAVE, hostLeave.code)
    }
}
