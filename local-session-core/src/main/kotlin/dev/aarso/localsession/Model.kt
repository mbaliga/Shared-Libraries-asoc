package dev.aarso.localsession

private val stableIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val topicPattern = Regex("[a-z0-9][a-z0-9._/-]{0,127}")

@JvmInline
value class SessionId(val value: String) {
    init {
        require(stableIdPattern.matches(value)) { "SessionId must be a stable 1-128 character token" }
    }
}

@JvmInline
value class ParticipantId(val value: String) {
    init {
        require(stableIdPattern.matches(value)) { "ParticipantId must be a stable 1-128 character token" }
    }
}

@JvmInline
value class MessageId(val value: String) {
    init {
        require(stableIdPattern.matches(value)) { "MessageId must be a stable 1-128 character token" }
    }
}

enum class ParticipantRole {
    HOST,
    COACH,
    PLAYER,
    OBSERVER,
}

enum class SessionPhase {
    LOBBY,
    ACTIVE,
    PAUSED,
    CLOSED,
}

data class ParticipantCandidate(
    val id: ParticipantId,
    val displayName: String,
    val roles: Set<ParticipantRole>,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= 80) { "displayName must not exceed 80 characters" }
        require(displayName.none(Char::isISOControl)) { "displayName must not contain control characters" }
        require(roles.isNotEmpty()) { "at least one role is required" }
    }
}

data class SessionParticipant(
    val id: ParticipantId,
    val displayName: String,
    val roles: Set<ParticipantRole>,
    val ready: Boolean,
    val joinedAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
)

data class SessionEvent(
    val sequence: Long,
    val messageId: MessageId,
    val senderId: ParticipantId,
    val topic: String,
    val contentType: String,
    val payload: String,
    val recipientIds: Set<ParticipantId>,
    val acceptedAtEpochMillis: Long,
)

data class LocalSessionSnapshot(
    val protocolVersion: Int,
    val sessionId: SessionId,
    val revision: Long,
    val phase: SessionPhase,
    val hostId: ParticipantId,
    val participants: List<SessionParticipant>,
    val recentEvents: List<SessionEvent>,
)

sealed interface SessionCommand {
    val sessionId: SessionId
    val messageId: MessageId
    val actorId: ParticipantId
}

data class JoinSession(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    val participant: ParticipantCandidate,
) : SessionCommand {
    override val actorId: ParticipantId = participant.id
}

data class LeaveSession(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    override val actorId: ParticipantId,
) : SessionCommand

data class SetParticipantReady(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    override val actorId: ParticipantId,
    val ready: Boolean,
) : SessionCommand

data class ChangeSessionPhase(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    override val actorId: ParticipantId,
    val phase: SessionPhase,
) : SessionCommand

data class PublishSessionEvent(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    override val actorId: ParticipantId,
    val topic: String,
    val payload: String,
    val contentType: String = "application/json",
    val recipientIds: Set<ParticipantId> = emptySet(),
) : SessionCommand {
    init {
        require(topicPattern.matches(topic)) { "topic must be a lowercase 1-128 character path" }
        require(contentType.isNotBlank() && contentType.length <= 100) {
            "contentType must be a non-blank value of at most 100 characters"
        }
    }
}

data class ParticipantHeartbeat(
    override val sessionId: SessionId,
    override val messageId: MessageId,
    override val actorId: ParticipantId,
) : SessionCommand

enum class RejectionCode {
    WRONG_SESSION,
    DUPLICATE_MESSAGE,
    SESSION_CLOSED,
    SESSION_FULL,
    PARTICIPANT_EXISTS,
    PARTICIPANT_NOT_FOUND,
    HOST_ROLE_RESERVED,
    HOST_CANNOT_LEAVE,
    HOST_ONLY,
    INVALID_PHASE_TRANSITION,
    UNKNOWN_RECIPIENT,
    PAYLOAD_TOO_LARGE,
}

sealed interface SessionChange {
    val revision: Long

    data class ParticipantJoined(
        override val revision: Long,
        val participant: SessionParticipant,
    ) : SessionChange

    data class ParticipantLeft(
        override val revision: Long,
        val participantId: ParticipantId,
    ) : SessionChange

    data class ReadyChanged(
        override val revision: Long,
        val participantId: ParticipantId,
        val ready: Boolean,
    ) : SessionChange

    data class PhaseChanged(
        override val revision: Long,
        val phase: SessionPhase,
    ) : SessionChange

    data class EventPublished(
        override val revision: Long,
        val event: SessionEvent,
    ) : SessionChange

    data class HeartbeatRecorded(
        override val revision: Long,
        val participantId: ParticipantId,
        val seenAtEpochMillis: Long,
    ) : SessionChange
}

sealed interface SessionResult {
    val snapshot: LocalSessionSnapshot

    data class Accepted(
        override val snapshot: LocalSessionSnapshot,
        val changes: List<SessionChange>,
        val duplicate: Boolean = false,
    ) : SessionResult

    data class Rejected(
        val code: RejectionCode,
        val reason: String,
        override val snapshot: LocalSessionSnapshot,
        val duplicate: Boolean = false,
    ) : SessionResult
}
