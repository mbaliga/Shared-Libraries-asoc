package dev.aarso.session

/** A short-lived, user-started local session. No account or server identity is implied. */
@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "session id must not be blank" } }
}

@JvmInline
value class ParticipantId(val value: String) {
    init { require(value.isNotBlank()) { "participant id must not be blank" } }
}

enum class SessionRole { HOST, PEER }

enum class SessionPacketType { HELLO, ACCEPTED, EVENT, ACK, LEAVE }

data class SessionPacket(
    val sessionId: SessionId,
    val sender: ParticipantId,
    val type: SessionPacketType,
    val sequence: Long,
    val eventId: String? = null,
    val payload: String? = null,
) {
    init {
        require(sequence >= 0) { "sequence must be >= 0" }
        if (type == SessionPacketType.EVENT) require(!eventId.isNullOrBlank()) { "events need eventId" }
    }
}

data class SessionSnapshot(
    val sessionId: SessionId,
    val localParticipant: ParticipantId,
    val participants: Set<ParticipantId>,
    val lastSequenceByParticipant: Map<ParticipantId, Long>,
    val appliedEventIds: Set<String>,
    val connected: Boolean,
)

/**
 * Deterministic event ledger used by every transport. Duplicate packets are harmless; stale
 * sequence numbers are rejected. Hosts may persist/replay the event list in their own storage.
 */
class LocalSessionLedger(
    val sessionId: SessionId,
    val localParticipant: ParticipantId,
    private val role: SessionRole,
) {
    private val participants = linkedSetOf(localParticipant)
    private val lastSequence = mutableMapOf<ParticipantId, Long>()
    private val appliedEventIds = linkedSetOf<String>()
    private var connected = false

    fun connect(participant: ParticipantId): Boolean {
        participants += participant
        connected = true
        return true
    }

    fun disconnect(participant: ParticipantId) {
        participants -= participant
        connected = participants.any { it != localParticipant }
    }

    fun accept(packet: SessionPacket): Boolean {
        require(packet.sessionId == sessionId) { "packet belongs to another session" }
        if (packet.type == SessionPacketType.EVENT) {
            val eventId = requireNotNull(packet.eventId)
            if (eventId in appliedEventIds) return false
            val previous = lastSequence[packet.sender] ?: -1L
            if (packet.sequence <= previous) return false
            lastSequence[packet.sender] = packet.sequence
            appliedEventIds += eventId
        }
        participants += packet.sender
        connected = true
        return true
    }

    fun localEvent(sequence: Long, eventId: String, payload: String): SessionPacket {
        require(sequence > (lastSequence[localParticipant] ?: -1L)) { "sequence must increase" }
        val packet = SessionPacket(sessionId, localParticipant, SessionPacketType.EVENT, sequence, eventId, payload)
        check(accept(packet)) { "local event was not accepted" }
        return packet
    }

    fun snapshot(): SessionSnapshot = SessionSnapshot(
        sessionId = sessionId,
        localParticipant = localParticipant,
        participants = participants.toSet(),
        lastSequenceByParticipant = lastSequence.toMap(),
        appliedEventIds = appliedEventIds.toSet(),
        connected = connected,
    )

    @Suppress("UNUSED_PARAMETER")
    fun role(): SessionRole = role
}

/** Transport boundary: Nearby/Bluetooth/LAN implementations belong to the consuming app. */
interface LocalSessionTransport {
    fun send(packet: SessionPacket)
    fun close()
}
