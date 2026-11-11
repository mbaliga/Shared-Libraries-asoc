package dev.aarso.platformports

/** A Bluetooth device address in canonical form, upper-case with colons: `AA:BB:CC:DD:EE:FF`. */
class BluetoothAddress private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is BluetoothAddress && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /** Accepts `:` or `-` separators or none, any case. Returns null if malformed. */
        fun parseOrNull(text: String): BluetoothAddress? {
            val t = text.trim()
            val hex = when {
                t.length == 17 && (t[2] == ':' || t[2] == '-') -> {
                    val sep = t[2]
                    if (t.indices.any { (it % 3 == 2) != (t[it] == sep) }) return null
                    t.filter { it != sep }
                }
                t.length == 12 -> t
                else -> return null
            }
            if (hex.length != 12 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            return BluetoothAddress(hex.uppercase().chunked(2).joinToString(":"))
        }
    }
}

/** Serial Port Profile service UUID, the usual RFCOMM service id. */
const val SPP_UUID: String = "00001101-0000-1000-8000-00805f9b34fb"

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

fun isValidServiceUuid(text: String): Boolean = UUID_PATTERN.matches(text)

interface RfcommConnection {
    /** Reads up to [max] bytes; returns -1 at end of stream. */
    fun read(buffer: ByteArray, max: Int): Int

    fun write(bytes: ByteArray)

    fun close()
}

sealed interface RfcommOutcome {
    data class Connected(val connection: RfcommConnection) : RfcommOutcome

    data class Unavailable(val reason: String) : RfcommOutcome
}

/** Bluetooth classic serial (RFCOMM), for devices such as headsets-as-sensors. User-initiated only. */
interface RfcommTransport : PortComponent {
    fun connect(address: BluetoothAddress, serviceUuid: String = SPP_UUID): RfcommOutcome
}
