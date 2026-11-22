package dev.ide.ui.backend.remote

/** Ints big-endian, strings as UTF-8 behind their byte length (-1 for null). */
class RemoteOut {
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
    fun double(v: Double) = long(v.toRawBits())
    fun float(v: Float) = int(v.toRawBits())
    fun bytes(v: ByteArray) { int(v.size); room(v.size); v.copyInto(bytes, size); size += v.size }
    /** A value that may be absent: whether it is there, then the value. */
    inline fun <T : Any> opt(v: T?, write: (T) -> Unit) { if (v == null) bool(false) else { bool(true); write(v) } }
    inline fun <K, V> map(items: Map<K, V>, key: (K) -> Unit, value: (V) -> Unit) {
        int(items.size)
        for ((k, v) in items) { key(k); value(v) }
    }
    /** A value typed only as `Any`: one of the plain kinds a settings map or a template's arguments hold. */
    fun any(v: Any?) {
        when (v) {
            null -> int(0)
            is String -> { int(1); string(v) }
            is Int -> { int(2); int(v) }
            is Long -> { int(3); long(v) }
            is Boolean -> { int(4); bool(v) }
            is Double -> { int(5); double(v) }
            is Float -> { int(6); float(v) }
            is List<*> -> { int(7); list(v) { any(it) } }
            is Set<*> -> { int(8); list(v.toList()) { any(it) } }
            is Map<*, *> -> { int(9); map(v, { any(it) }, { any(it) }) }
            is Enum<*> -> { int(1); string(v.name) }
            else -> error("a ${v::class.simpleName} cannot cross to the backend as a plain value")
        }
    }
    fun done(): ByteArray = bytes.copyOf(size)
}

class RemoteIn(private val bytes: ByteArray) {
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
    fun str(): String = string() ?: error("a string was expected")
    fun double(): Double = Double.fromBits(long())
    fun float(): Float = Float.fromBits(int())
    fun bytes(): ByteArray { val n = int(); return bytes.copyOfRange(at, at + n).also { at += n } }
    inline fun <T : Any> opt(read: () -> T): T? = if (bool()) read() else null
    inline fun <K, V> map(key: () -> K, value: () -> V): Map<K, V> {
        val n = int()
        val out = LinkedHashMap<K, V>(n)
        repeat(n) { val k = key(); out[k] = value() }
        return out
    }
    fun any(): Any? = when (val kind = int()) {
        0 -> null
        1 -> str()
        2 -> int()
        3 -> long()
        4 -> bool()
        5 -> double()
        6 -> float()
        7 -> list { any() }
        8 -> list { any() }.toSet()
        9 -> map({ any() }, { any() })
        else -> error("unknown plain value $kind")
    }
    val size: Int get() = bytes.size
}
