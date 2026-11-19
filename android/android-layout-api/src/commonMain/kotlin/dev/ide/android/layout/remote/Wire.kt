package dev.ide.android.layout.remote

/** Appends values to a growing byte array: ints big-endian, strings as UTF-8 with their byte length first. */
internal class WireOut(initial: Int = 256) {
    private var bytes = ByteArray(initial)
    private var size = 0

    private fun room(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun int(v: Int) {
        room(4)
        bytes[size++] = (v ushr 24).toByte()
        bytes[size++] = (v ushr 16).toByte()
        bytes[size++] = (v ushr 8).toByte()
        bytes[size++] = v.toByte()
    }

    fun bool(v: Boolean) = int(if (v) 1 else 0)
    fun float(v: Float) = int(v.toRawBits())

    fun string(v: String?) {
        if (v == null) { int(-1); return }
        val utf8 = v.encodeToByteArray()
        int(utf8.size)
        room(utf8.size)
        utf8.copyInto(bytes, size)
        size += utf8.size
    }

    fun optionalInt(v: Int?) { bool(v != null); if (v != null) int(v) }

    fun bytes(v: ByteArray?) {
        if (v == null) { int(-1); return }
        int(v.size)
        room(v.size)
        v.copyInto(bytes, size)
        size += v.size
    }

    /** Packed ARGB pixels, each as its four big-endian bytes. */
    fun ints(v: IntArray?) {
        if (v == null) { int(-1); return }
        int(v.size)
        room(v.size * 4)
        for (c in v) {
            bytes[size++] = (c ushr 24).toByte()
            bytes[size++] = (c ushr 16).toByte()
            bytes[size++] = (c ushr 8).toByte()
            bytes[size++] = c.toByte()
        }
    }

    fun <T> list(items: Collection<T>, write: (T) -> Unit) {
        int(items.size)
        for (item in items) write(item)
    }

    fun done(): ByteArray = bytes.copyOf(size)
}

/** Reads what [WireOut] wrote, in the same order. */
internal class WireIn(private val bytes: ByteArray) {
    private var at = 0

    fun int(): Int {
        val v = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
        at += 4
        return v
    }

    fun bool(): Boolean = int() != 0
    fun float(): Float = Float.fromBits(int())

    fun stringOrNull(): String? {
        val n = int()
        if (n < 0) return null
        val s = bytes.decodeToString(at, at + n)
        at += n
        return s
    }

    fun string(): String = stringOrNull() ?: ""
    fun optionalInt(): Int? = if (bool()) int() else null

    fun bytesOrNull(): ByteArray? {
        val n = int()
        if (n < 0) return null
        return bytes.copyOfRange(at, at + n).also { at += n }
    }

    fun intsOrNull(): IntArray? {
        val n = int()
        if (n < 0) return null
        val out = IntArray(n)
        var p = at
        for (i in 0 until n) {
            out[i] = ((bytes[p].toInt() and 0xff) shl 24) or ((bytes[p + 1].toInt() and 0xff) shl 16) or
                ((bytes[p + 2].toInt() and 0xff) shl 8) or (bytes[p + 3].toInt() and 0xff)
            p += 4
        }
        at = p
        return out
    }

    fun <T> list(read: () -> T): List<T> = List(int()) { read() }
}
