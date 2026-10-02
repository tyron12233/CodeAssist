package dev.ide.vm

import kotlin.jvm.JvmField

/** The elements of a floor list (`ArrayList`, `List.of`, `Arrays.asList`), and what it allows. */
internal class JList(
    @JvmField val items: MutableList<Any?>,
    val mutable: Boolean = true,
    val resizable: Boolean = true,
) {
    fun checkMutable(vm: Vm) {
        if (!mutable) vm.throwVm("java/lang/UnsupportedOperationException", null)
    }

    fun checkResizable(vm: Vm) {
        if (!mutable || !resizable) vm.throwVm("java/lang/UnsupportedOperationException", null)
    }

    fun checkIndex(vm: Vm, index: Int) {
        if (index < 0 || index >= items.size) {
            vm.throwVm("java/lang/IndexOutOfBoundsException", "Index $index out of bounds for length ${items.size}")
        }
    }

    fun get(vm: Vm, index: Int): Any? {
        checkIndex(vm, index)
        return items[index]
    }
}

/** A `HashMap` entry, which is also the `Map.Entry` interpreted code sees. */
internal class JNode(
    override val vmClass: VmClass,
    @JvmField val hash: Int,
    @JvmField val key: Any?,
    @JvmField var value: Any?,
    @JvmField var next: JNode?,
) : HostObject {
    @JvmField var before: JNode? = null
    @JvmField var after: JNode? = null
}

/**
 * Java's `HashMap` algorithm, reproduced: the same hash spreading, power-of-two tables, tail insertion and
 * order-preserving split on resize. The point is not speed but ITERATION ORDER, which is observable: a
 * program that prints a `HashSet` must print it the way the JVM does, or the VM is not running that program.
 * Tree bins are not reproduced (they only form past eight collisions in one bucket and keep the same
 * iteration order there).
 *
 * [linked] adds `LinkedHashMap`'s insertion (or, with [accessOrder], access) order. [identity] compares keys
 * by reference, as `IdentityHashMap` does.
 */
internal class JHashMap(
    private val vm: Vm,
    val linked: Boolean,
    private val accessOrder: Boolean = false,
    initialCapacity: Int = -1,
    private val loadFactor: Float = 0.75f,
    private val identity: Boolean = false,
) {
    private val nodeClass = vm.loadClass("java/util/HashMap\$Node")
    private var table: Array<JNode?>? = null
    var size = 0
        private set
    private var threshold = if (initialCapacity < 0) 0 else tableSizeFor(initialCapacity)
    private var head: JNode? = null
    private var tail: JNode? = null
    var mutable = true

    /** Called after an insertion, for `LinkedHashMap.removeEldestEntry`. */
    var afterInsert: ((eldest: JNode) -> Unit)? = null

    private fun spread(key: Any?): Int {
        if (key == null) return 0
        val h = if (identity) identityHash(key) else vm.vmHashCode(key)
        return h xor (h ushr 16)
    }

    private fun same(key: Any?, other: Any?): Boolean =
        key === other || (!identity && key != null && vm.vmEquals(key, other))

    fun getNode(key: Any?): JNode? {
        val tab = table ?: return null
        val h = spread(key)
        var e = tab[(tab.size - 1) and h]
        while (e != null) {
            if (e.hash == h && same(key, e.key)) return e
            e = e.next
        }
        return null
    }

    fun get(key: Any?): Any? {
        val e = getNode(key) ?: return null
        if (accessOrder) moveToEnd(e)
        return e.value
    }

    fun containsKey(key: Any?): Boolean = getNode(key) != null

    fun containsValue(value: Any?): Boolean = nodes().any { vm.vmEquals(value, it.value) }

    fun checkMutable() {
        if (!mutable) vm.throwVm("java/lang/UnsupportedOperationException", null)
    }

    /** Java's `putVal`: returns the previous value. */
    fun put(key: Any?, value: Any?, onlyIfAbsent: Boolean = false): Any? {
        val tab = table ?: resize()
        val h = spread(key)
        val i = (tab.size - 1) and h
        val first = tab[i]
        if (first == null) {
            tab[i] = newNode(h, key, value)
        } else {
            var q: JNode = first
            while (true) {
                if (q.hash == h && same(key, q.key)) {
                    val old = q.value
                    if (!onlyIfAbsent || old == null) q.value = value
                    if (accessOrder) moveToEnd(q)
                    return old
                }
                val n = q.next
                if (n == null) { q.next = newNode(h, key, value); break }
                q = n
            }
        }
        if (++size > threshold) resize()
        afterInsert?.let { hook -> head?.let(hook) }
        return null
    }

    fun remove(key: Any?): JNode? {
        val tab = table ?: return null
        val h = spread(key)
        val i = (tab.size - 1) and h
        var prev: JNode? = null
        var e = tab[i]
        while (e != null) {
            if (e.hash == h && same(key, e.key)) {
                if (prev == null) tab[i] = e.next else prev.next = e.next
                size--
                if (linked) unlink(e)
                return e
            }
            prev = e
            e = e.next
        }
        return null
    }

    fun clear() {
        table?.fill(null)
        size = 0
        head = null
        tail = null
    }

    /** `putMapEntries`' presizing, so a map copied from another has the table Java would give it. */
    fun presize(count: Int) {
        if (table == null && count > 0) {
            val ft = count / loadFactor + 1.0f
            val t = if (ft < MAXIMUM_CAPACITY) ft.toInt() else MAXIMUM_CAPACITY
            if (t > threshold) threshold = tableSizeFor(t)
        } else if (table != null && count > threshold) {
            resize()
        }
    }

    /** The entries in iteration order, as a snapshot. */
    fun nodes(): List<JNode> {
        val out = ArrayList<JNode>(size)
        if (linked) {
            var e = head
            while (e != null) { out.add(e); e = e.after }
        } else {
            val tab = table ?: return out
            for (b in tab) {
                var e = b
                while (e != null) { out.add(e); e = e.next }
            }
        }
        return out
    }

    fun keys(): List<Any?> = nodes().map { it.key }

    private fun newNode(h: Int, key: Any?, value: Any?): JNode {
        val n = JNode(nodeClass, h, key, value, null)
        if (linked) {
            val last = tail
            tail = n
            if (last == null) head = n else { n.before = last; last.after = n }
        }
        return n
    }

    private fun unlink(e: JNode) {
        val b = e.before
        val a = e.after
        e.before = null
        e.after = null
        if (b == null) head = a else b.after = a
        if (a == null) tail = b else a.before = b
    }

    private fun moveToEnd(e: JNode) {
        if (!linked || tail === e) return
        unlink(e)
        val last = tail
        tail = e
        if (last == null) head = e else { e.before = last; last.after = e }
    }

    private fun resize(): Array<JNode?> {
        val oldTab = table
        val oldCap = oldTab?.size ?: 0
        val oldThr = threshold
        var newCap: Int
        var newThr = 0
        if (oldCap > 0) {
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Int.MAX_VALUE
                return oldTab!!
            }
            newCap = oldCap shl 1
            if (newCap < MAXIMUM_CAPACITY && oldCap >= DEFAULT_CAPACITY) newThr = oldThr shl 1
        } else if (oldThr > 0) {
            newCap = oldThr
        } else {
            newCap = DEFAULT_CAPACITY
            newThr = (DEFAULT_CAPACITY * 0.75f).toInt()
        }
        if (newThr == 0) {
            val ft = newCap * loadFactor
            newThr = if (newCap < MAXIMUM_CAPACITY && ft < MAXIMUM_CAPACITY) ft.toInt() else Int.MAX_VALUE
        }
        threshold = newThr
        val newTab = arrayOfNulls<JNode>(newCap)
        table = newTab
        if (oldTab != null) {
            for (j in 0 until oldCap) {
                val e = oldTab[j] ?: continue
                oldTab[j] = null
                if (e.next == null) {
                    newTab[e.hash and (newCap - 1)] = e
                    continue
                }
                var loHead: JNode? = null
                var loTail: JNode? = null
                var hiHead: JNode? = null
                var hiTail: JNode? = null
                var cur: JNode? = e
                while (cur != null) {
                    val next = cur.next
                    if (cur.hash and oldCap == 0) {
                        if (loTail == null) loHead = cur else loTail.next = cur
                        loTail = cur
                    } else {
                        if (hiTail == null) hiHead = cur else hiTail.next = cur
                        hiTail = cur
                    }
                    cur = next
                }
                if (loTail != null) { loTail.next = null; newTab[j] = loHead }
                if (hiTail != null) { hiTail.next = null; newTab[j + oldCap] = hiHead }
            }
        }
        return newTab
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
        const val MAXIMUM_CAPACITY = 1 shl 30

        fun tableSizeFor(cap: Int): Int {
            val n = -1 ushr (cap - 1).countLeadingZeroBits()
            return if (n < 0) 1 else if (n >= MAXIMUM_CAPACITY) MAXIMUM_CAPACITY else n + 1
        }
    }
}

/** A live `keySet()`/`values()`/`entrySet()` of a [JHashMap]. */
internal class JHashMapView(override val vmClass: VmClass, val map: JHashMap, val kind: Int) : HostObject {
    fun snapshot(): List<Any?> = when (kind) {
        KEYS -> map.keys()
        VALUES -> map.nodes().map { it.value }
        else -> map.nodes()
    }

    companion object {
        const val KEYS = 0
        const val VALUES = 1
        const val ENTRIES = 2
    }
}

/**
 * `TreeMap`/`TreeSet`: keys kept sorted in an array and found by binary search, so equality is the
 * comparator's (`compare == 0`), as Java's are. Insertion is O(n), which no preview notices.
 */
internal class JSorted(val comparator: Comparator<Any?>, val isSet: Boolean) {
    val keys = ArrayList<Any?>()
    val values = ArrayList<Any?>()

    /** The index of [key], or `-(insertion point) - 1`. */
    fun find(key: Any?): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = comparator.compare(keys[mid], key)
            if (c < 0) lo = mid + 1 else if (c > 0) hi = mid - 1 else return mid
        }
        return -(lo + 1)
    }

    fun put(key: Any?, value: Any?): Any? {
        val i = find(key)
        if (i >= 0) { val old = values[i]; values[i] = value; return old }
        keys.add(-i - 1, key)
        values.add(-i - 1, value)
        return null
    }

    fun indexOrNull(key: Any?): Int? = find(key).takeIf { it >= 0 }

    fun removeAt(i: Int): Any? { keys.removeAt(i); return values.removeAt(i) }

    /** The first index whose key is >= [key] (`ceiling`), or > it when [strict] (`higher`). */
    fun lowerBound(key: Any?, strict: Boolean): Int {
        val i = find(key)
        return if (i >= 0) (if (strict) i + 1 else i) else -i - 1
    }

    /** The last index whose key is <= [key] (`floor`), or < it when [strict] (`lower`). */
    fun upperBound(key: Any?, strict: Boolean): Int {
        val i = find(key)
        return if (i >= 0) (if (strict) i - 1 else i) else -i - 2
    }
}
