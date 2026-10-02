package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.HostObject
import dev.ide.vm.JHashMap
import dev.ide.vm.JHashMapView
import dev.ide.vm.JNode
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.Vm
import dev.ide.vm.VmClass
import dev.ide.vm.VmObject
import dev.ide.vm.VmRefArray
import dev.ide.vm.collectionToString
import dev.ide.vm.define
import dev.ide.vm.elementsOf

/** A map or set the floor implements and interpreted code cannot subclass (`Map.of`, `Collections.emptySet`). */
internal class HostMap(override val vmClass: VmClass, val map: JHashMap) : HostObject

/** What a `HashSet` maps every element to, as Java's does. */
private val PRESENT = Any()

internal fun Call.jm(): JHashMap = when (val o = r(0)) {
    is VmObject -> o.native as JHashMap
    is HostMap -> o.map
    else -> error("not a floor map: $o")
}

internal fun Vm.hostMap(entries: List<Pair<Any?, Any?>>, mutable: Boolean): HostMap {
    val m = JHashMap(this, linked = true)
    for ((k, v) in entries) m.put(k, v)
    m.mutable = mutable
    return HostMap(loadClass("java/util/HostMap"), m)
}

internal fun Vm.hostSet(elements: List<Any?>, mutable: Boolean): HostMap {
    val m = JHashMap(this, linked = true)
    for (e in elements) m.put(e, PRESENT)
    m.mutable = mutable
    return HostMap(loadClass("java/util/HostSet"), m)
}

internal fun Vm.emptyHostMap(): HostMap = hostMap(emptyList(), mutable = false)
internal fun Vm.emptyHostSet(): HostMap = hostSet(emptyList(), mutable = false)

/** The entries of any map, as (key, value) pairs: directly for a floor map, through `entrySet()` otherwise. */
internal fun Vm.entriesOf(map: Any?): List<Pair<Any?, Any?>> {
    val backing = (map as? VmObject)?.native ?: map
    if (backing is JHashMap) return backing.nodes().map { it.key to it.value }
    if (map is HostMap) return map.map.nodes().map { it.key to it.value }
    val entries = callVirtual(map, "entrySet", "()Ljava/util/Set;")
    return elementsOf(entries).map { e -> entryKey(e) to entryValue(e) }
}

private fun Vm.entryKey(e: Any?): Any? = if (e is JNode) e.key else callVirtual(e, "getKey", "()Ljava/lang/Object;")
private fun Vm.entryValue(e: Any?): Any? = if (e is JNode) e.value else callVirtual(e, "getValue", "()Ljava/lang/Object;")

private fun Vm.mapToString(self: Any, entries: List<Pair<Any?, Any?>>): String =
    entries.joinToString(", ", "{", "}") { (k, v) ->
        (if (k === self) "(this Map)" else vmToString(k)) + "=" + (if (v === self) "(this Map)" else vmToString(v))
    }

private fun Vm.mapEquals(self: Any, entries: List<Pair<Any?, Any?>>, other: Any?): Boolean {
    if (self === other) return true
    if (other == null || !isInstance(other, loadClass("java/util/Map"))) return false
    val theirs = entriesOf(other)
    if (theirs.size != entries.size) return false
    for ((k, v) in entries) {
        val ov = callVirtual(other, "get", "(Ljava/lang/Object;)Ljava/lang/Object;", k)
        if (v == null) {
            if (ov != null || !(callVirtual(other, "containsKey", "(Ljava/lang/Object;)Z", k) as Boolean)) return false
        } else if (!vmEquals(v, ov)) return false
    }
    return true
}

private fun Vm.mapHash(entries: List<Pair<Any?, Any?>>): Int = entries.sumOf { (k, v) -> vmHashCode(k) xor vmHashCode(v) }

private fun Vm.view(map: JHashMap, kind: Int): JHashMapView = JHashMapView(loadClass("java/util/HashMap\$View"), map, kind)

internal fun ClassDefs.registerMaps() {
    define("java/util/Map") {
        asInterface()
        method("getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            retRef(if (v != null || vm.callVirtual(r(0), "containsKey", "(Ljava/lang/Object;)Z", r(1)) as Boolean) v else r(2))
        }
        method("forEach", "(Ljava/util/function/BiConsumer;)V") {
            for ((k, v) in vm.entriesOf(r(0))) vm.callVirtual(r(1), "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", k, v)
        }
        method("putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            if (v == null) vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), r(2))
            retRef(v)
        }
        method("computeIfAbsent", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            if (v != null) { retRef(v); return@method }
            val created = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            if (created != null) vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), created)
            retRef(created)
        }
        method("computeIfPresent", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            if (v == null) { retRef(null); return@method }
            val next = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), v)
            if (next == null) vm.callVirtual(r(0), "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            else vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), next)
            retRef(next)
        }
        method("compute", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            val next = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), v)
            if (next == null) { if (v != null) vm.callVirtual(r(0), "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1)) }
            else vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), next)
            retRef(next)
        }
        method("merge", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            val next = if (v == null) r(2) else vm.callVirtual(r(3), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", v, r(2))
            if (next == null) vm.callVirtual(r(0), "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            else vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), next)
            retRef(next)
        }
        method("replaceAll", "(Ljava/util/function/BiFunction;)V") {
            for ((k, v) in vm.entriesOf(r(0))) {
                val next = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", k, v)
                vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", k, next)
            }
        }
        method("remove", "(Ljava/lang/Object;Ljava/lang/Object;)Z") {
            val v = vm.callVirtual(r(0), "get", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            val hit = vm.vmEquals(v, r(2)) && (v != null || vm.callVirtual(r(0), "containsKey", "(Ljava/lang/Object;)Z", r(1)) as Boolean)
            if (hit) vm.callVirtual(r(0), "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
            ret(hit)
        }
        method("replace", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") {
            val has = vm.callVirtual(r(0), "containsKey", "(Ljava/lang/Object;)Z", r(1)) as Boolean
            retRef(if (has) vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), r(2)) else null)
        }
        static("of", "()Ljava/util/Map;") { retRef(vm.emptyHostMap()) }
        for (n in 1..10) {
            static("of", "(" + "Ljava/lang/Object;Ljava/lang/Object;".repeat(n) + ")Ljava/util/Map;") {
                retRef(vm.hostMap(List(n) { r(it * 2) to r(it * 2 + 1) }, mutable = false))
            }
        }
        static("entry", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map\$Entry;") {
            retRef(JNode(vm.loadClass("java/util/HashMap\$Node"), 0, r(0), r(1), null))
        }
        static("ofEntries", "([Ljava/util/Map\$Entry;)Ljava/util/Map;") {
            retRef(vm.hostMap((r(0) as VmRefArray).data.map { vm.entryKey(it) to vm.entryValue(it) }, mutable = false))
        }
        static("copyOf", "(Ljava/util/Map;)Ljava/util/Map;") { retRef(vm.hostMap(vm.entriesOf(r(0)), mutable = false)) }
    }
    define("java/util/SortedMap") { asInterface(); implements("java/util/Map") }
    define("java/util/NavigableMap") { asInterface(); implements("java/util/SortedMap") }
    define("java/util/concurrent/ConcurrentMap") { asInterface(); implements("java/util/Map") }
    define("java/util/Map\$Entry") { asInterface() }

    define("java/util/HashMap\$Node") {
        implements("java/util/Map\$Entry")
        method("getKey", "()Ljava/lang/Object;") { retRef((r(0) as JNode).key) }
        method("getValue", "()Ljava/lang/Object;") { retRef((r(0) as JNode).value) }
        method("setValue", "(Ljava/lang/Object;)Ljava/lang/Object;") { val n = r(0) as JNode; val old = n.value; n.value = r(1); retRef(old) }
        method("equals", "(Ljava/lang/Object;)Z") {
            val n = r(0) as JNode
            val o = r(1)
            ret(o != null && vm.isInstance(o, vm.loadClass("java/util/Map\$Entry")) &&
                vm.vmEquals(n.key, vm.entryKey(o)) && vm.vmEquals(n.value, vm.entryValue(o)))
        }
        method("hashCode", "()I") { val n = r(0) as JNode; ret(vm.vmHashCode(n.key) xor vm.vmHashCode(n.value)) }
        method("toString", "()Ljava/lang/String;") { val n = r(0) as JNode; retRef(vm.vmToString(n.key) + "=" + vm.vmToString(n.value)) }
    }

    define("java/util/HashMap\$View") {
        superName = "java/util/AbstractSet"
        implements("java/util/Set", "java/util/Collection")
        fun Call.view() = r(0) as JHashMapView
        method("size", "()I") { ret(view().map.size) }
        method("isEmpty", "()Z") { ret(view().map.size == 0) }
        method("contains", "(Ljava/lang/Object;)Z") {
            val v = view(); val o = r(1)
            ret(when (v.kind) {
                JHashMapView.KEYS -> v.map.containsKey(o)
                JHashMapView.VALUES -> v.map.containsValue(o)
                else -> o != null && v.map.getNode(vm.entryKey(o))?.let { vm.vmEquals(it.value, vm.entryValue(o)) } == true
            })
        }
        method("remove", "(Ljava/lang/Object;)Z") {
            val v = view(); v.map.checkMutable()
            ret(when (v.kind) {
                JHashMapView.KEYS -> v.map.remove(r(1)) != null
                JHashMapView.VALUES -> v.map.nodes().firstOrNull { vm.vmEquals(r(1), it.value) }?.let { v.map.remove(it.key) } != null
                else -> v.map.remove(vm.entryKey(r(1))) != null
            })
        }
        method("clear", "()V") { view().map.checkMutable(); view().map.clear() }
        method("iterator", "()Ljava/util/Iterator;") { retRef(viewIterator(vm, view())) }
        method("toArray", "()[Ljava/lang/Object;") { retRef(vm.objectArray(view().snapshot())) }
        method("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;") { retRef(vm.toTypedArrayLike(view().snapshot(), r(1))) }
        method("forEach", "(Ljava/util/function/Consumer;)V") {
            for (e in view().snapshot()) vm.callVirtual(r(1), "accept", "(Ljava/lang/Object;)V", e)
        }
        method("toString", "()Ljava/lang/String;") { retRef(vm.collectionToString(r(0)!!, view().snapshot())) }
        method("add", "(Ljava/lang/Object;)Z") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
    }

    define("java/util/AbstractMap") {
        asAbstract()
        implements("java/util/Map")
        ctor("()V") {}
        method("size", "()I") { ret(vm.callVirtual(vm.callVirtual(r(0), "entrySet", "()Ljava/util/Set;"), "size", "()I") as Int) }
        method("isEmpty", "()Z") { ret(vm.callVirtual(r(0), "size", "()I") as Int == 0) }
        method("get", "(Ljava/lang/Object;)Ljava/lang/Object;") {
            val k = r(1); retRef(vm.entriesOf(r(0)).firstOrNull { vm.vmEquals(k, it.first) }?.second)
        }
        method("containsKey", "(Ljava/lang/Object;)Z") { val k = r(1); ret(vm.entriesOf(r(0)).any { vm.vmEquals(k, it.first) }) }
        method("containsValue", "(Ljava/lang/Object;)Z") { val v = r(1); ret(vm.entriesOf(r(0)).any { vm.vmEquals(v, it.second) }) }
        method("put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
        method("remove", "(Ljava/lang/Object;)Ljava/lang/Object;") {
            val it = vm.callVirtual(vm.callVirtual(r(0), "entrySet", "()Ljava/util/Set;"), "iterator", "()Ljava/util/Iterator;")
            var found: Any? = null
            while (vm.callVirtual(it, "hasNext", "()Z") as Boolean) {
                val e = vm.callVirtual(it, "next", "()Ljava/lang/Object;")
                if (vm.vmEquals(r(1), vm.entryKey(e))) { found = vm.entryValue(e); vm.callVirtual(it, "remove", "()V"); break }
            }
            retRef(found)
        }
        method("putAll", "(Ljava/util/Map;)V") {
            for ((k, v) in vm.entriesOf(r(1))) vm.callVirtual(r(0), "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", k, v)
        }
        method("clear", "()V") { vm.callVirtual(vm.callVirtual(r(0), "entrySet", "()Ljava/util/Set;"), "clear", "()V") }
        method("keySet", "()Ljava/util/Set;") { retRef(vm.hostSet(vm.entriesOf(r(0)).map { it.first }, mutable = false)) }
        method("values", "()Ljava/util/Collection;") { retRef(vm.hostList(vm.entriesOf(r(0)).map { it.second }.toMutableList(), false, false)) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(vm.mapEquals(r(0)!!, vm.entriesOf(r(0)), r(1))) }
        method("hashCode", "()I") { ret(vm.mapHash(vm.entriesOf(r(0)))) }
        method("toString", "()Ljava/lang/String;") { retRef(vm.mapToString(r(0)!!, vm.entriesOf(r(0)))) }
    }
    define("java/util/AbstractMap\$SimpleEntry") { simpleEntry() }
    define("java/util/AbstractMap\$SimpleImmutableEntry") { simpleEntry() }

    for (name in listOf("java/util/HashMap", "java/util/LinkedHashMap", "java/util/concurrent/ConcurrentHashMap",
        "java/util/WeakHashMap", "java/util/IdentityHashMap", "java/util/Hashtable")) {
        define(name) {
            superName = when (name) {
                "java/util/LinkedHashMap" -> "java/util/HashMap"
                else -> "java/util/AbstractMap"
            }
            implements("java/util/Map", "java/lang/Cloneable", "java/io/Serializable")
            if (name == "java/util/concurrent/ConcurrentHashMap") implements("java/util/concurrent/ConcurrentMap")
            val linked = name == "java/util/LinkedHashMap"
            val identity = name == "java/util/IdentityHashMap"
            fun Call.install(capacity: Int = -1, loadFactor: Float = 0.75f, accessOrder: Boolean = false): JHashMap {
                val map = JHashMap(vm, linked = linked, accessOrder = accessOrder, initialCapacity = capacity, loadFactor = loadFactor, identity = identity)
                val self = self()
                self.native = map
                if (linked && self.cls.name != name) {
                    val hook = vm.selectVirtual(self.cls, "removeEldestEntry(Ljava/util/Map\$Entry;)Z")
                    if (hook != null && !hook.owner.isNativeClass()) map.afterInsert = { eldest ->
                        if (vm.callVirtual(self, "removeEldestEntry", "(Ljava/util/Map\$Entry;)Z", eldest) as Boolean) map.remove(eldest.key)
                    }
                }
                return map
            }
            ctor("()V") { install() }
            ctor("(I)V") { if (i(1) < 0) vm.throwVm("java/lang/IllegalArgumentException", "Illegal initial capacity: ${i(1)}"); install(i(1)) }
            ctor("(IF)V") { install(i(1), f(2)) }
            ctor("(IFZ)V") { install(i(1), f(2), z(3)) }
            ctor("(IFI)V") { install(i(1), f(2)) }
            ctor("(Ljava/util/Map;)V") {
                val entries = vm.entriesOf(r(1))
                val map = install()
                map.presize(entries.size)
                for ((k, v) in entries) map.put(k, v)
            }
            ctor("(Ljava/util/Comparator;)V") { install() }
            mapMethods()
            method("removeEldestEntry", "(Ljava/util/Map\$Entry;)Z") { ret(false) }
            method("clone", "()Ljava/lang/Object;") {
                val copy = VmObject(self().cls)
                val src = jm()
                val map = JHashMap(vm, linked = linked, identity = identity)
                map.presize(src.size)
                for (n in src.nodes()) map.put(n.key, n.value)
                copy.native = map
                retRef(copy)
            }
        }
    }
    define("java/util/TreeSet") {
        superName = "java/util/AbstractSet"
        implements("java/util/NavigableSet", "java/lang/Cloneable", "java/io/Serializable")
        fun Call.t(): JSorted = self().native as JSorted
        ctor("()V") { self().native = JSorted(vm.comparatorFunction(null), isSet = true) }
        ctor("(Ljava/util/Comparator;)V") { self().native = JSorted(vm.comparatorFunction(r(1)), isSet = true) }
        ctor("(Ljava/util/Collection;)V") {
            val t = JSorted(vm.comparatorFunction(null), isSet = true)
            for (e in vm.elementsOf(r(1))) t.put(e, null)
            self().native = t
        }
        method("size", "()I") { ret(t().keys.size) }
        method("isEmpty", "()Z") { ret(t().keys.isEmpty()) }
        method("contains", "(Ljava/lang/Object;)Z") { ret(t().indexOrNull(r(1)) != null) }
        method("add", "(Ljava/lang/Object;)Z") {
            val t = t()
            if (t.indexOrNull(r(1)) != null) ret(false) else { t.put(r(1), null); ret(true) }
        }
        method("remove", "(Ljava/lang/Object;)Z") { val t = t(); val i = t.indexOrNull(r(1)); if (i != null) t.removeAt(i); ret(i != null) }
        method("clear", "()V") { t().keys.clear(); t().values.clear() }
        method("first", "()Ljava/lang/Object;") { val k = t().keys; if (k.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(k.first()) }
        method("last", "()Ljava/lang/Object;") { val k = t().keys; if (k.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(k.last()) }
        method("pollFirst", "()Ljava/lang/Object;") { val t = t(); retRef(if (t.keys.isEmpty()) null else t.keys[0].also { t.removeAt(0) }) }
        method("pollLast", "()Ljava/lang/Object;") { val t = t(); retRef(if (t.keys.isEmpty()) null else t.keys.last().also { t.removeAt(t.keys.size - 1) }) }
        method("ceiling", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.lowerBound(r(1), false))) }
        method("higher", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.lowerBound(r(1), true))) }
        method("floor", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.upperBound(r(1), false))) }
        method("lower", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.upperBound(r(1), true))) }
        method("iterator", "()Ljava/util/Iterator;") { retRef(vm.sortedIterator(t(), descending = false)) }
        method("descendingIterator", "()Ljava/util/Iterator;") { retRef(vm.sortedIterator(t(), descending = true)) }
        method("toArray", "()[Ljava/lang/Object;") { retRef(vm.objectArray(t().keys.toList())) }
        method("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;") { retRef(vm.toTypedArrayLike(t().keys.toList(), r(1))) }
        method("toString", "()Ljava/lang/String;") { retRef(vm.collectionToString(r(0)!!, t().keys.toList())) }
        method("comparator", "()Ljava/util/Comparator;") { retRef(null) }
    }
    define("java/util/TreeMap") {
        superName = "java/util/AbstractMap"
        implements("java/util/NavigableMap", "java/lang/Cloneable", "java/io/Serializable")
        fun Call.t(): JSorted = self().native as JSorted
        ctor("()V") { self().native = JSorted(vm.comparatorFunction(null), isSet = false) }
        ctor("(Ljava/util/Comparator;)V") { self().native = JSorted(vm.comparatorFunction(r(1)), isSet = false) }
        ctor("(Ljava/util/Map;)V") {
            val t = JSorted(vm.comparatorFunction(null), isSet = false)
            for ((k, v) in vm.entriesOf(r(1))) t.put(k, v)
            self().native = t
        }
        method("size", "()I") { ret(t().keys.size) }
        method("isEmpty", "()Z") { ret(t().keys.isEmpty()) }
        method("get", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.indexOrNull(r(1))?.let { t.values[it] }) }
        method("containsKey", "(Ljava/lang/Object;)Z") { ret(t().indexOrNull(r(1)) != null) }
        method("put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { retRef(t().put(r(1), r(2))) }
        method("remove", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.indexOrNull(r(1))?.let { t.removeAt(it) }) }
        method("clear", "()V") { t().keys.clear(); t().values.clear() }
        method("firstKey", "()Ljava/lang/Object;") { val k = t().keys; if (k.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(k.first()) }
        method("lastKey", "()Ljava/lang/Object;") { val k = t().keys; if (k.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(k.last()) }
        method("ceilingKey", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.lowerBound(r(1), false))) }
        method("higherKey", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.lowerBound(r(1), true))) }
        method("floorKey", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.upperBound(r(1), false))) }
        method("lowerKey", "(Ljava/lang/Object;)Ljava/lang/Object;") { val t = t(); retRef(t.keys.getOrNull(t.upperBound(r(1), true))) }
        method("entrySet", "()Ljava/util/Set;") {
            val t = t()
            val node = vm.loadClass("java/util/HashMap\$Node")
            retRef(vm.hostSet(t.keys.indices.map { JNode(node, 0, t.keys[it], t.values[it], null) }, mutable = false))
        }
        method("keySet", "()Ljava/util/Set;") { retRef(vm.hostSet(t().keys.toList(), mutable = false)) }
        method("values", "()Ljava/util/Collection;") { retRef(vm.hostList(t().values.toMutableList(), false, false)) }
    }

    define("java/util/Dictionary") { asAbstract(); ctor("()V") {} }
    define("java/util/Properties") {
        superName = "java/util/Hashtable"
        ctor("()V") { self().native = JHashMap(vm, linked = false) }
        ctor("(Ljava/util/Properties;)V") { self().native = JHashMap(vm, linked = false) }
        method("getProperty", "(Ljava/lang/String;)Ljava/lang/String;") { retRef(jm().get(r(1)) as? String) }
        method("getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") { retRef(jm().get(r(1)) as? String ?: r(2)) }
        method("setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;") { retRef(jm().put(r(1), r(2))) }
        method("stringPropertyNames", "()Ljava/util/Set;") { retRef(vm.hostSet(jm().keys().filterIsInstance<String>(), mutable = false)) }
        method("propertyNames", "()Ljava/util/Enumeration;") { retRef(vm.hostIterator(jm().keys().iterator())) }
    }
    define("java/util/HostMap") {
        superName = "java/util/AbstractMap"
        implements("java/util/Map")
        mapMethods()
    }

    for (name in listOf("java/util/HashSet", "java/util/LinkedHashSet")) {
        define(name) {
            superName = if (name == "java/util/LinkedHashSet") "java/util/HashSet" else "java/util/AbstractSet"
            implements("java/util/Set", "java/lang/Cloneable", "java/io/Serializable")
            val linked = name != "java/util/HashSet"
            ctor("()V") { self().native = JHashMap(vm, linked = linked) }
            ctor("(I)V") { self().native = JHashMap(vm, linked = linked, initialCapacity = i(1)) }
            ctor("(IF)V") { self().native = JHashMap(vm, linked = linked, initialCapacity = i(1), loadFactor = f(2)) }
            ctor("(Ljava/util/Comparator;)V") { self().native = JHashMap(vm, linked = linked) }
            ctor("(Ljava/util/Collection;)V") {
                val items = vm.elementsOf(r(1))
                val map = JHashMap(vm, linked = linked, initialCapacity = maxOf((items.size / .75f).toInt() + 1, 16))
                for (e in items) map.put(e, PRESENT)
                self().native = map
            }
            setMethods()
            method("clone", "()Ljava/lang/Object;") {
                val copy = VmObject(self().cls)
                val map = JHashMap(vm, linked = linked)
                for (k in jm().keys()) map.put(k, PRESENT)
                copy.native = map
                retRef(copy)
            }
        }
    }
    define("java/util/HostSet") {
        superName = "java/util/AbstractSet"
        implements("java/util/Set")
        setMethods()
    }
}

private fun VmClass.isNativeClass(): Boolean = name.startsWith("java/")

internal fun Vm.sortedIterator(t: JSorted, descending: Boolean): JIterator {
    val snapshot = if (descending) t.keys.reversed() else t.keys.toList()
    var index = 0
    var last: Any? = null
    var hasLast = false
    return object : JIterator() {
        override val vmClass: VmClass = loadClass("java/util/HostIterator")
        override fun hasNext() = index < snapshot.size
        override fun next(vm: Vm): Any? {
            if (index >= snapshot.size) vm.throwVm("java/util/NoSuchElementException", null)
            last = snapshot[index++]; hasLast = true
            return last
        }
        override fun remove(vm: Vm) {
            if (!hasLast) vm.throwVm("java/lang/IllegalStateException", null)
            t.indexOrNull(last)?.let { t.removeAt(it) }
            hasLast = false
        }
    }
}

private fun viewIterator(vm: Vm, view: JHashMapView): JIterator {
    val nodes = view.map.nodes()
    var index = 0
    var last: JNode? = null
    return object : JIterator() {
        override val vmClass: VmClass = vm.loadClass("java/util/HostIterator")
        override fun hasNext() = index < nodes.size
        override fun next(vm: Vm): Any? {
            if (index >= nodes.size) vm.throwVm("java/util/NoSuchElementException", null)
            val n = nodes[index++]
            last = n
            return when (view.kind) { JHashMapView.KEYS -> n.key; JHashMapView.VALUES -> n.value; else -> n }
        }
        override fun remove(vm: Vm) {
            val n = last ?: vm.throwVm("java/lang/IllegalStateException", null)
            view.map.checkMutable()
            view.map.remove(n.key)
            last = null
        }
    }
}

private fun NativeClassBuilder.simpleEntry() {
    implements("java/util/Map\$Entry", "java/io/Serializable")
    field("key", "Ljava/lang/Object;")
    field("value", "Ljava/lang/Object;")
    ctor("(Ljava/lang/Object;Ljava/lang/Object;)V") { self().refs[0] = r(1); self().refs[1] = r(2) }
    ctor("(Ljava/util/Map\$Entry;)V") { self().refs[0] = vm.entryKey(r(1)); self().refs[1] = vm.entryValue(r(1)) }
    method("getKey", "()Ljava/lang/Object;") { retRef(self().refs[0]) }
    method("getValue", "()Ljava/lang/Object;") { retRef(self().refs[1]) }
    method("setValue", "(Ljava/lang/Object;)Ljava/lang/Object;") { val old = self().refs[1]; self().refs[1] = r(1); retRef(old) }
    method("hashCode", "()I") { ret(vm.vmHashCode(self().refs[0]) xor vm.vmHashCode(self().refs[1])) }
    method("equals", "(Ljava/lang/Object;)Z") {
        val o = r(1)
        ret(o != null && vm.isInstance(o, vm.loadClass("java/util/Map\$Entry")) &&
            vm.vmEquals(self().refs[0], vm.entryKey(o)) && vm.vmEquals(self().refs[1], vm.entryValue(o)))
    }
    method("toString", "()Ljava/lang/String;") { retRef(vm.vmToString(self().refs[0]) + "=" + vm.vmToString(self().refs[1])) }
}

private fun NativeClassBuilder.mapMethods() {
    method("size", "()I") { ret(jm().size) }
    method("isEmpty", "()Z") { ret(jm().size == 0) }
    method("get", "(Ljava/lang/Object;)Ljava/lang/Object;") { retRef(jm().get(r(1))) }
    method("getOrDefault", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { val n = jm().getNode(r(1)); retRef(if (n != null) n.value else r(2)) }
    method("containsKey", "(Ljava/lang/Object;)Z") { ret(jm().containsKey(r(1))) }
    method("containsValue", "(Ljava/lang/Object;)Z") { ret(jm().containsValue(r(1))) }
    method("put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { val m = jm(); m.checkMutable(); retRef(m.put(r(1), r(2))) }
    method("putIfAbsent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { val m = jm(); m.checkMutable(); retRef(m.put(r(1), r(2), onlyIfAbsent = true)) }
    method("remove", "(Ljava/lang/Object;)Ljava/lang/Object;") { val m = jm(); m.checkMutable(); retRef(m.remove(r(1))?.value) }
    method("putAll", "(Ljava/util/Map;)V") {
        val m = jm(); m.checkMutable()
        val entries = vm.entriesOf(r(1))
        m.presize(entries.size)
        for ((k, v) in entries) m.put(k, v)
    }
    method("clear", "()V") { val m = jm(); m.checkMutable(); m.clear() }
    method("keySet", "()Ljava/util/Set;") { retRef(vm.view(jm(), JHashMapView.KEYS)) }
    method("values", "()Ljava/util/Collection;") { retRef(vm.view(jm(), JHashMapView.VALUES)) }
    method("entrySet", "()Ljava/util/Set;") { retRef(vm.view(jm(), JHashMapView.ENTRIES)) }
    method("forEach", "(Ljava/util/function/BiConsumer;)V") {
        for (n in jm().nodes()) vm.callVirtual(r(1), "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", n.key, n.value)
    }
    method("computeIfAbsent", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;") {
        val m = jm()
        val existing = m.getNode(r(1))
        if (existing?.value != null) { retRef(existing.value); return@method }
        val created = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", r(1))
        if (created != null) { m.checkMutable(); m.put(r(1), created) }
        retRef(created)
    }
    method("computeIfPresent", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
        val m = jm()
        val v = m.getNode(r(1))?.value
        if (v == null) { retRef(null); return@method }
        val next = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), v)
        m.checkMutable()
        if (next == null) m.remove(r(1)) else m.put(r(1), next)
        retRef(next)
    }
    method("compute", "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
        val m = jm()
        val v = m.getNode(r(1))?.value
        val next = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", r(1), v)
        m.checkMutable()
        if (next == null) m.remove(r(1)) else m.put(r(1), next)
        retRef(next)
    }
    method("merge", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;") {
        val m = jm()
        val v = m.getNode(r(1))?.value
        val next = if (v == null) r(2) else vm.callVirtual(r(3), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", v, r(2))
        m.checkMutable()
        if (next == null) m.remove(r(1)) else m.put(r(1), next)
        retRef(next)
    }
    method("replace", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") {
        val n = jm().getNode(r(1))
        if (n == null) { retRef(null); return@method }
        val old = n.value; n.value = r(2); retRef(old)
    }
    method("replaceAll", "(Ljava/util/function/BiFunction;)V") {
        for (n in jm().nodes()) n.value = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", n.key, n.value)
    }
    method("equals", "(Ljava/lang/Object;)Z") { ret(vm.mapEquals(r(0)!!, jm().nodes().map { it.key to it.value }, r(1))) }
    method("hashCode", "()I") { ret(vm.mapHash(jm().nodes().map { it.key to it.value })) }
    method("toString", "()Ljava/lang/String;") { retRef(vm.mapToString(r(0)!!, jm().nodes().map { it.key to it.value })) }
}

private fun NativeClassBuilder.setMethods() {
    method("size", "()I") { ret(jm().size) }
    method("isEmpty", "()Z") { ret(jm().size == 0) }
    method("contains", "(Ljava/lang/Object;)Z") { ret(jm().containsKey(r(1))) }
    method("add", "(Ljava/lang/Object;)Z") { val m = jm(); m.checkMutable(); ret(m.put(r(1), PRESENT) == null) }
    method("remove", "(Ljava/lang/Object;)Z") { val m = jm(); m.checkMutable(); ret(m.remove(r(1)) != null) }
    method("clear", "()V") { val m = jm(); m.checkMutable(); m.clear() }
    method("iterator", "()Ljava/util/Iterator;") { retRef(viewIterator(vm, JHashMapView(vm.loadClass("java/util/HashMap\$View"), jm(), JHashMapView.KEYS))) }
    method("toArray", "()[Ljava/lang/Object;") { retRef(vm.objectArray(jm().keys())) }
    method("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;") { retRef(vm.toTypedArrayLike(jm().keys(), r(1))) }
    method("containsAll", "(Ljava/util/Collection;)Z") { val m = jm(); ret(vm.elementsOf(r(1)).all { m.containsKey(it) }) }
    method("addAll", "(Ljava/util/Collection;)Z") {
        val m = jm(); m.checkMutable()
        var changed = false
        for (e in vm.elementsOf(r(1))) if (m.put(e, PRESENT) == null) changed = true
        ret(changed)
    }
    method("removeAll", "(Ljava/util/Collection;)Z") {
        val m = jm(); m.checkMutable()
        var changed = false
        for (e in vm.elementsOf(r(1))) if (m.remove(e) != null) changed = true
        ret(changed)
    }
    method("retainAll", "(Ljava/util/Collection;)Z") {
        val m = jm(); m.checkMutable()
        val keep = vm.elementsOf(r(1))
        var changed = false
        for (k in m.keys()) if (keep.none { vm.vmEquals(it, k) }) { m.remove(k); changed = true }
        ret(changed)
    }
    method("removeIf", "(Ljava/util/function/Predicate;)Z") {
        val m = jm(); m.checkMutable()
        var changed = false
        for (k in m.keys()) if (vm.callVirtual(r(1), "test", "(Ljava/lang/Object;)Z", k) as Boolean) { m.remove(k); changed = true }
        ret(changed)
    }
    method("forEach", "(Ljava/util/function/Consumer;)V") {
        for (k in jm().keys()) vm.callVirtual(r(1), "accept", "(Ljava/lang/Object;)V", k)
    }
    method("equals", "(Ljava/lang/Object;)Z") { ret(vm.setEquals(r(0)!!, r(1))) }
    method("hashCode", "()I") { ret(jm().keys().sumOf { vm.vmHashCode(it) }) }
    method("toString", "()Ljava/lang/String;") { retRef(vm.collectionToString(r(0)!!, jm().keys())) }
}
