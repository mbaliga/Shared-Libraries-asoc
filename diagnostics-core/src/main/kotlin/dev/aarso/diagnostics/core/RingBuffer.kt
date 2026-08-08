package dev.aarso.diagnostics.core

/**
 * Fixed-capacity ring. Preallocated, no allocation on write, no file I/O during capture — the
 * module must not show up in the numbers it is measuring.
 *
 * Not thread-safe by itself; callers pin writes to one thread (observations on whichever thread
 * produces them, one source per ring) and only [snapshot] crosses threads, under the session lock,
 * at export time.
 */
class RingBuffer<T>(val capacity: Int) {

    private val items = arrayOfNulls<Any?>(capacity)
    private var head = 0
    private var count = 0

    /** Total ever offered, including entries since overwritten — lets a report say what it dropped. */
    var totalOffered: Long = 0
        private set

    val size: Int get() = count
    val dropped: Long get() = maxOf(0L, totalOffered - count)

    fun add(item: T) {
        items[head] = item
        head = (head + 1) % capacity
        if (count < capacity) count++
        totalOffered++
    }

    fun clear() {
        java.util.Arrays.fill(items, null)
        head = 0; count = 0; totalOffered = 0
    }

    /** Oldest-first copy. The only operation that allocates, and it runs once, at export. */
    @Suppress("UNCHECKED_CAST")
    fun snapshot(): List<T> {
        if (count == 0) return emptyList()
        val out = ArrayList<T>(count)
        val start = (head - count + capacity) % capacity
        for (i in 0 until count) out.add(items[(start + i) % capacity] as T)
        return out
    }
}
