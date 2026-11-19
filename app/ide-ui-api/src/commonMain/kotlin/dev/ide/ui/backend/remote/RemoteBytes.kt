package dev.ide.ui.backend.remote

/** Ints big-endian, strings as UTF-8 behind their byte length (-1 for null). */
internal class RemoteOut {
    private var bytes = ByteArray(1024)
    private var size = 0
    private fun room(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
    fun int(v: Int) {
        room(4)
        bytes[size++] = (v ushr 24).toByte(); bytes[size++] = (v ushr 16).toByte()
        bytes[size++] = (v ushr 8).toByte(); bytes[size++] = v.toByte()
    }
    fun string(v: String?) {
        if (v == null) { int(-1); return }
        val utf8 = v.encodeToByteArray()
        int(utf8.size); room(utf8.size)
        utf8.copyInto(bytes, size); size += utf8.size
    }
    fun bool(v: Boolean) = int(if (v) 1 else 0)
    fun long(v: Long) { int((v ushr 32).toInt()); int(v.toInt()) }
    fun <T> list(items: List<T>, write: (T) -> Unit) { int(items.size); for (item in items) write(item) }
    fun done(): ByteArray = bytes.copyOf(size)
}

internal class RemoteIn(private val bytes: ByteArray) {
    private var at = 0
    fun int(): Int {
        val v = (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)
        at += 4
        return v
    }
    fun string(): String? {
        val n = int()
        if (n < 0) return null
        return bytes.decodeToString(at, at + n).also { at += n }
    }
    fun bool(): Boolean = int() != 0
    fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)
    fun <T> list(read: () -> T): List<T> = List(int()) { read() }
    val size: Int get() = bytes.size
}
