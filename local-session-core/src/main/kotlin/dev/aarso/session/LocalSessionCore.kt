package dev.aarso.session

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import kotlin.concurrent.thread

private const val MAX_SESSION_FRAME_CHARS = 64 * 1024

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

fun interface SessionPacketListener {
    fun onPacket(packet: SessionPacket)
}

/** A short-lived human-readable join code for an explicitly paired local session. */
@JvmInline
value class SessionPairingCode(val value: String) {
    init {
        require(value.length == 6 && value.all(Char::isDigit)) {
            "pairing code must be exactly six digits"
        }
    }
}

/**
 * Small line-framed TCP transport for beta/local-network sessions. An optional six-digit pairing
 * code gates the socket before packet readers are exposed. Discovery, code display/entry, TLS or
 * platform identity remain responsibilities of the consuming app.
 */
class TcpLocalSessionTransport private constructor(
    private val socket: Socket,
    private val listener: SessionPacketListener,
) : LocalSessionTransport {
    private val writer = socket.getOutputStream().bufferedWriter()

    init {
        thread(name = "aarso-session-reader", isDaemon = true) {
            runCatching {
                val reader = socket.getInputStream().bufferedReader()
                generateSequence { reader.readLine() }
                    .forEach { line ->
                        require(line.length <= MAX_SESSION_FRAME_CHARS) { "session packet is too large" }
                        listener.onPacket(SessionPacketCodec.decode(line))
                    }
            }
        }
    }

    override fun send(packet: SessionPacket) {
        synchronized(writer) {
            val frame = SessionPacketCodec.encode(packet)
            require(frame.length <= MAX_SESSION_FRAME_CHARS) { "session packet is too large" }
            writer.appendLine(frame)
            writer.flush()
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        fun connect(
            host: String,
            port: Int,
            listener: SessionPacketListener,
            pairingCode: SessionPairingCode? = null,
        ): TcpLocalSessionTransport {
            val socket = Socket(host, port)
            if (pairingCode != null) performClientHandshake(socket, pairingCode)
            return TcpLocalSessionTransport(socket, listener)
        }

        internal fun from(
            socket: Socket,
            listener: SessionPacketListener,
            pairingCode: SessionPairingCode? = null,
        ): TcpLocalSessionTransport {
            if (pairingCode != null) performHostHandshake(socket, pairingCode)
            return TcpLocalSessionTransport(socket, listener)
        }

        private fun performClientHandshake(socket: Socket, pairingCode: SessionPairingCode) {
            socket.soTimeout = 5_000
            val writer = socket.getOutputStream().bufferedWriter()
            writer.appendLine("AUTH|${encodePart(pairingCode.value)}")
            writer.flush()
            val response = socket.getInputStream().bufferedReader().readLine()
            socket.soTimeout = 0
            if (response != "AUTH_OK") {
                socket.close()
                throw SecurityException("local session pairing was rejected")
            }
        }

        private fun performHostHandshake(socket: Socket, pairingCode: SessionPairingCode) {
            try {
                socket.soTimeout = 5_000
                val request = socket.getInputStream().bufferedReader().readLine()
                val supplied = request?.takeIf { it.startsWith("AUTH|") }
                    ?.substringAfter("AUTH|")
                    ?.let(::decodePart)
                val writer = socket.getOutputStream().bufferedWriter()
                if (supplied != pairingCode.value) {
                    writer.appendLine("AUTH_REJECT")
                    writer.flush()
                    socket.close()
                    throw SecurityException("local session pairing was rejected")
                }
                writer.appendLine("AUTH_OK")
                writer.flush()
                socket.soTimeout = 0
            } catch (timeout: SocketTimeoutException) {
                socket.close()
                throw timeout
            }
        }

        private fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodePart(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    }
}

class TcpLocalSessionHost(
    private val serverSocket: ServerSocket,
    private val listener: SessionPacketListener,
    private val onConnection: (LocalSessionTransport) -> Unit,
    private val pairingCode: SessionPairingCode?,
) : AutoCloseable {
    val port: Int get() = serverSocket.localPort

    fun start(): TcpLocalSessionHost {
        thread(name = "aarso-session-acceptor", isDaemon = true) {
            runCatching {
                while (!serverSocket.isClosed) {
                    val socket = serverSocket.accept()
                    runCatching {
                        onConnection(TcpLocalSessionTransport.from(socket, listener, pairingCode))
                    }.onFailure { socket.close() }
                }
            }
        }
        return this
    }

    override fun close() {
        serverSocket.close()
    }

    companion object {
        fun bind(
            port: Int = 0,
            listener: SessionPacketListener,
            onConnection: (LocalSessionTransport) -> Unit,
            pairingCode: SessionPairingCode? = null,
        ): TcpLocalSessionHost = TcpLocalSessionHost(ServerSocket(port), listener, onConnection, pairingCode)
    }
}

private object SessionPacketCodec {
    fun encode(packet: SessionPacket): String = listOf(
        packet.type.name,
        packet.sessionId.value,
        packet.sender.value,
        packet.sequence.toString(),
        packet.eventId,
        packet.payload,
    ).mapIndexed { index, value -> if (index == 0) value ?: "" else encodePart(value) }
        .joinToString("|")

    fun decode(line: String): SessionPacket {
        val fields = line.split('|')
        require(fields.size == 6) { "invalid session packet" }
        return SessionPacket(
            sessionId = SessionId(decodePart(fields[1])),
            sender = ParticipantId(decodePart(fields[2])),
            type = SessionPacketType.valueOf(fields[0]),
            sequence = decodePart(fields[3]).toLong(),
            eventId = decodeNullable(fields[4]),
            payload = decodeNullable(fields[5]),
        )
    }

    private fun encodePart(value: String?): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString((value ?: "").toByteArray(Charsets.UTF_8))

    private fun decodePart(value: String): String = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    private fun decodeNullable(value: String): String? = decodePart(value).ifEmpty { null }
}
