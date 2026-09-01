package dev.aarso.localsession

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.LinkedHashMap

class LocalSessionEngine private constructor(
    private val sessionId: SessionId,
    private val hostId: ParticipantId,
    host: SessionParticipant,
    private val maxParticipants: Int,
    private val recentEventLimit: Int,
    private val processedMessageLimit: Int,
    private val maxPayloadBytes: Int,
    private val nowEpochMillis: () -> Long,
) {
    private val participants = LinkedHashMap<ParticipantId, SessionParticipant>().apply {
        put(host.id, host)
    }
    private val recentEvents = ArrayDeque<SessionEvent>()
    private val processedMessages = LinkedHashMap<MessageId, Boolean>()
    private var revision = 0L
    private var eventSequence = 0L
    private var phase = SessionPhase.LOBBY

    companion object {
        const val PROTOCOL_VERSION: Int = 1

        fun create(
            sessionId: SessionId,
            hostId: ParticipantId,
            hostDisplayName: String,
            hostRoles: Set<ParticipantRole> = setOf(ParticipantRole.HOST, ParticipantRole.COACH),
            maxParticipants: Int = 8,
            recentEventLimit: Int = 128,
            processedMessageLimit: Int = 1_024,
            maxPayloadBytes: Int = 64 * 1_024,
            nowEpochMillis: () -> Long = System::currentTimeMillis,
        ): LocalSessionEngine {
            require(maxParticipants >= 1) { "maxParticipants must be at least one" }
            require(recentEventLimit >= 0) { "recentEventLimit must not be negative" }
            require(processedMessageLimit >= 1) { "processedMessageLimit must be at least one" }
            require(maxPayloadBytes >= 1) { "maxPayloadBytes must be at least one" }
            require(ParticipantRole.HOST in hostRoles) { "hostRoles must include HOST" }
            val candidate = ParticipantCandidate(hostId, hostDisplayName, hostRoles)
            val now = nowEpochMillis()
            return LocalSessionEngine(
                sessionId = sessionId,
                hostId = hostId,
                host = candidate.toParticipant(now),
                maxParticipants = maxParticipants,
                recentEventLimit = recentEventLimit,
                processedMessageLimit = processedMessageLimit,
                maxPayloadBytes = maxPayloadBytes,
                nowEpochMillis = nowEpochMillis,
            )
        }
    }

    @Synchronized
    fun snapshot(): LocalSessionSnapshot = currentSnapshot()

    @Synchronized
    fun apply(command: SessionCommand): SessionResult {
        if (command.sessionId != sessionId) {
            return SessionResult.Rejected(
                RejectionCode.WRONG_SESSION,
                "Command belongs to another session",
                currentSnapshot(),
            )
        }
        processedMessages[command.messageId]?.let { wasAccepted ->
            return if (wasAccepted) {
                SessionResult.Accepted(currentSnapshot(), emptyList(), duplicate = true)
            } else {
                SessionResult.Rejected(
                    RejectionCode.DUPLICATE_MESSAGE,
                    "Rejected command was already processed",
                    currentSnapshot(),
                    duplicate = true,
                )
            }
        }
        if (phase == SessionPhase.CLOSED) {
            return reject(command.messageId, RejectionCode.SESSION_CLOSED, "Session is closed")
        }

        return when (command) {
            is JoinSession -> join(command)
            is LeaveSession -> leave(command)
            is SetParticipantReady -> setReady(command)
            is ChangeSessionPhase -> changePhase(command)
            is PublishSessionEvent -> publish(command)
            is ParticipantHeartbeat -> heartbeat(command)
        }
    }

    private fun join(command: JoinSession): SessionResult {
        if (participants.containsKey(command.participant.id)) {
            return reject(command.messageId, RejectionCode.PARTICIPANT_EXISTS, "Participant ID is already present")
        }
        if (participants.size >= maxParticipants) {
            return reject(command.messageId, RejectionCode.SESSION_FULL, "Session has reached its participant limit")
        }
        if (ParticipantRole.HOST in command.participant.roles) {
            return reject(command.messageId, RejectionCode.HOST_ROLE_RESERVED, "Only the session creator may be HOST")
        }
        val participant = command.participant.toParticipant(nowEpochMillis())
        participants[participant.id] = participant
        return accept(command.messageId) { nextRevision ->
            SessionChange.ParticipantJoined(nextRevision, participant)
        }
    }

    private fun leave(command: LeaveSession): SessionResult {
        if (command.actorId == hostId) {
            return reject(command.messageId, RejectionCode.HOST_CANNOT_LEAVE, "Host must close the session")
        }
        if (participants.remove(command.actorId) == null) {
            return reject(command.messageId, RejectionCode.PARTICIPANT_NOT_FOUND, "Participant is not in the session")
        }
        return accept(command.messageId) { nextRevision ->
            SessionChange.ParticipantLeft(nextRevision, command.actorId)
        }
    }

    private fun setReady(command: SetParticipantReady): SessionResult {
        val participant = participants[command.actorId]
            ?: return reject(command.messageId, RejectionCode.PARTICIPANT_NOT_FOUND, "Participant is not in the session")
        val updated = participant.copy(ready = command.ready, lastSeenAtEpochMillis = nowEpochMillis())
        participants[command.actorId] = updated
        return accept(command.messageId) { nextRevision ->
            SessionChange.ReadyChanged(nextRevision, command.actorId, command.ready)
        }
    }

    private fun changePhase(command: ChangeSessionPhase): SessionResult {
        if (command.actorId != hostId) {
            return reject(command.messageId, RejectionCode.HOST_ONLY, "Only the host may change the session phase")
        }
        if (!phase.canTransitionTo(command.phase)) {
            return reject(
                command.messageId,
                RejectionCode.INVALID_PHASE_TRANSITION,
                "Cannot transition from $phase to ${command.phase}",
            )
        }
        phase = command.phase
        return accept(command.messageId) { nextRevision ->
            SessionChange.PhaseChanged(nextRevision, phase)
        }
    }

    private fun publish(command: PublishSessionEvent): SessionResult {
        val participant = participants[command.actorId]
            ?: return reject(command.messageId, RejectionCode.PARTICIPANT_NOT_FOUND, "Participant is not in the session")
        val unknownRecipient = command.recipientIds.firstOrNull { it !in participants }
        if (unknownRecipient != null) {
            return reject(command.messageId, RejectionCode.UNKNOWN_RECIPIENT, "Recipient is not in the session")
        }
        if (command.payload.toByteArray(StandardCharsets.UTF_8).size > maxPayloadBytes) {
            return reject(command.messageId, RejectionCode.PAYLOAD_TOO_LARGE, "Payload exceeds the configured byte limit")
        }
        val now = nowEpochMillis()
        participants[command.actorId] = participant.copy(lastSeenAtEpochMillis = now)
        eventSequence += 1
        val event = SessionEvent(
            sequence = eventSequence,
            messageId = command.messageId,
            senderId = command.actorId,
            topic = command.topic,
            contentType = command.contentType,
            payload = command.payload,
            recipientIds = command.recipientIds.toSet(),
            acceptedAtEpochMillis = now,
        )
        if (recentEventLimit > 0) {
            recentEvents.addLast(event)
            while (recentEvents.size > recentEventLimit) recentEvents.removeFirst()
        }
        return accept(command.messageId) { nextRevision ->
            SessionChange.EventPublished(nextRevision, event)
        }
    }

    private fun heartbeat(command: ParticipantHeartbeat): SessionResult {
        val participant = participants[command.actorId]
            ?: return reject(command.messageId, RejectionCode.PARTICIPANT_NOT_FOUND, "Participant is not in the session")
        val now = nowEpochMillis()
        participants[command.actorId] = participant.copy(lastSeenAtEpochMillis = now)
        return accept(command.messageId) { nextRevision ->
            SessionChange.HeartbeatRecorded(nextRevision, command.actorId, now)
        }
    }

    private fun accept(messageId: MessageId, change: (Long) -> SessionChange): SessionResult.Accepted {
        revision += 1
        remember(messageId, accepted = true)
        return SessionResult.Accepted(currentSnapshot(), listOf(change(revision)))
    }

    private fun reject(messageId: MessageId, code: RejectionCode, reason: String): SessionResult.Rejected {
        remember(messageId, accepted = false)
        return SessionResult.Rejected(code, reason, currentSnapshot())
    }

    private fun remember(messageId: MessageId, accepted: Boolean) {
        processedMessages[messageId] = accepted
        while (processedMessages.size > processedMessageLimit) {
            val oldest = processedMessages.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun currentSnapshot() = LocalSessionSnapshot(
        protocolVersion = PROTOCOL_VERSION,
        sessionId = sessionId,
        revision = revision,
        phase = phase,
        hostId = hostId,
        participants = participants.values.toList(),
        recentEvents = recentEvents.toList(),
    )
}

private fun ParticipantCandidate.toParticipant(now: Long) = SessionParticipant(
    id = id,
    displayName = displayName,
    roles = roles.toSet(),
    ready = false,
    joinedAtEpochMillis = now,
    lastSeenAtEpochMillis = now,
)

private fun SessionPhase.canTransitionTo(next: SessionPhase): Boolean = when (this) {
    SessionPhase.LOBBY -> next == SessionPhase.ACTIVE || next == SessionPhase.CLOSED
    SessionPhase.ACTIVE -> next == SessionPhase.PAUSED || next == SessionPhase.CLOSED
    SessionPhase.PAUSED -> next == SessionPhase.ACTIVE || next == SessionPhase.CLOSED
    SessionPhase.CLOSED -> false
}
