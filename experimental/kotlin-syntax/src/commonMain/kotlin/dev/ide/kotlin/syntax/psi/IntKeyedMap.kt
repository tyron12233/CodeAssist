package dev.ide.kotlin.syntax.psi

/**
 * An open-addressing map from an `Int` key to a non-null value, for the per-session element caches keyed by
 * node index.
 *
 * A `HashMap<Int, V>` boxes its key on every `get`, and the element cache is read on every parent step and
 * child scan of every tree walk: profiled over a whole module's diagnostics, the boxing was about a seventh
 * of everything the analysis allocated. This one stores keys in an `IntArray` and allocates only when it
 * grows. It also takes about a quarter of the memory per entry, which matters for the parses the source
 * model keeps for every file. Not thread-safe, like the map it replaces.
 */
internal class IntKeyedMap<V : Any>(expected: Int = 8) {
    private var keys: IntArray
    private var values: Array<Any?>
    private var size = 0

    init {
        var cap = 8
        while (cap < expected * 2) cap = cap shl 1
        keys = IntArray(cap)
        values = arrayOfNulls(cap)
    }

    private fun slot(key: Int, mask: Int): Int {
        val h = key * -0x61c88647 // golden-ratio multiply spreads consecutive indices
        return (h xor (h ushr 16)) and mask
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: Int): V? {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            val v = values[i] ?: return null
            if (keys[i] == key) return v as V
            i = (i + 1) and mask
        }
    }

    operator fun set(key: Int, value: V) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            if (values[i] == null) {
                keys[i] = key; values[i] = value; size++
                return
            }
            if (keys[i] == key) {
                values[i] = value
                return
            }
            i = (i + 1) and mask
        }
    }

    inline fun getOrPut(key: Int, create: () -> V): V = get(key) ?: create().also { set(key, it) }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2)
        values = arrayOfNulls(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) {
            @Suppress("UNCHECKED_CAST")
            val v = oldValues[i] as V? ?: continue
            set(oldKeys[i], v)
        }
    }
}
