package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.HostObject
import dev.ide.vm.JList
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.Vm
import dev.ide.vm.VmClass
import dev.ide.vm.VmObject
import dev.ide.vm.VmRefArray
import dev.ide.vm.collectionToString
import dev.ide.vm.define
import dev.ide.vm.elementsOf
import dev.ide.vm.forEachElement
import dev.ide.vm.setStaticRef

/** A list the floor implements in Kotlin and never lets interpreted code subclass (`List.of`, `Arrays.asList`). */
internal class HostList(override val vmClass: VmClass, val list: JList) : HostObject

/** An iterator the floor implements: over a [JList], or over any Kotlin iterator. */
internal abstract class JIterator : HostObject {
    abstract fun hasNext(): Boolean
    abstract fun next(vm: Vm): Any?
    open fun remove(vm: Vm): Unit = vm.throwVm("java/lang/UnsupportedOperationException", "remove")
}

internal class JListIterator(override val vmClass: VmClass, val list: JList, var cursor: Int) : JIterator() {
    private var last = -1
    override fun hasNext() = cursor < list.items.size
    override fun next(vm: Vm): Any? {
        if (cursor >= list.items.size) vm.throwVm("java/util/NoSuchElementException", null)
        last = cursor
        return list.items[cursor++]
    }
    fun hasPrevious() = cursor > 0
    fun previous(vm: Vm): Any? {
        if (cursor <= 0) vm.throwVm("java/util/NoSuchElementException", null)
        last = --cursor
        return list.items[cursor]
    }
    override fun remove(vm: Vm) {
        if (last < 0) vm.throwVm("java/lang/IllegalStateException", null)
        list.checkResizable(vm)
        list.items.removeAt(last)
        if (last < cursor) cursor--
        last = -1
    }
    fun set(vm: Vm, v: Any?) {
        if (last < 0) vm.throwVm("java/lang/IllegalStateException", null)
        list.checkMutable(vm)
        list.items[last] = v
    }
    fun add(vm: Vm, v: Any?) {
        list.checkResizable(vm)
        list.items.add(cursor++, v)
        last = -1
    }
}

internal class JSeqIterator(override val vmClass: VmClass, private val source: MutableIterator<Any?>, private val onRemove: (() -> Unit)? = null) : JIterator() {
    override fun hasNext() = source.hasNext()
    override fun next(vm: Vm): Any? {
        if (!source.hasNext()) vm.throwVm("java/util/NoSuchElementException", null)
        return source.next()
    }
    override fun remove(vm: Vm) {
        if (onRemove != null) onRemove.invoke() else source.remove()
    }
}

internal fun Vm.hostIterator(items: Iterator<Any?>): JIterator =
    JSeqIterator(loadClass("java/util/HostIterator"), object : MutableIterator<Any?> {
        override fun hasNext() = items.hasNext()
        override fun next() = items.next()
        override fun remove() = throwVm("java/lang/UnsupportedOperationException", "remove")
    })

internal fun Vm.hostList(items: MutableList<Any?>, mutable: Boolean, resizable: Boolean): HostList =
    HostList(loadClass("java/util/HostList"), JList(items, mutable, resizable))

/** The [JList] behind a list receiver, whichever kind it is. */
internal fun Call.jl(): JList = when (val o = r(0)) {
    is VmObject -> o.native as JList
    is HostList -> o.list
    else -> error("not a floor list: $o")
}

/** `Comparable.compareTo`, directly for the values the floor represents itself. */
internal fun Vm.compareNatural(a: Any?, b: Any?): Int {
    if (a == null || b == null) throwVm("java/lang/NullPointerException", "compare with null")
    return when {
        a is String && b is String -> a.compareTo(b)
        a is Int && b is Int -> a.compareTo(b)
        a is Long && b is Long -> a.compareTo(b)
        a is Double && b is Double -> a.compareTo(b)
        a is Float && b is Float -> a.compareTo(b)
        a is Char && b is Char -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        else -> callVirtual(a, "compareTo", "(Ljava/lang/Object;)I", b) as Int
    }
}

internal fun Vm.comparatorFunction(comparator: Any?): Comparator<Any?> =
    if (comparator == null) Comparator { a, b -> compareNatural(a, b) }
    else Comparator { a, b -> callVirtual(comparator, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", a, b) as Int }

/** `toArray(T[])`: fill [target] when it is big enough (null-terminating), else a new array of its type. */
internal fun Vm.toTypedArrayLike(elements: List<Any?>, target: Any?): VmRefArray {
    val t = target as VmRefArray? ?: throwVm("java/lang/NullPointerException", "toArray(null)")
    if (t.data.size >= elements.size) {
        for (k in elements.indices) t.data[k] = elements[k]
        if (t.data.size > elements.size) t.data[elements.size] = null
        return t
    }
    return VmRefArray(t.cls, elements.toTypedArray())
}

internal fun Vm.objectArray(elements: List<Any?>): VmRefArray = newRefArray("java/lang/Object", elements.toTypedArray())

internal fun Vm.listEquals(self: Any, elements: List<Any?>, other: Any?): Boolean {
    if (self === other) return true
    if (other == null || !isInstance(other, loadClass("java/util/List"))) return false
    val others = elementsOf(other)
    if (others.size != elements.size) return false
    for (k in elements.indices) if (!vmEquals(elements[k], others[k])) return false
    return true
}

internal fun Vm.listHash(elements: List<Any?>): Int {
    var h = 1
    for (e in elements) h = 31 * h + vmHashCode(e)
    return h
}

/** A fixed-size list that writes through to an array, as `Arrays.asList` must. */
private class ArrayBackedList(val data: Array<Any?>) : AbstractMutableList<Any?>() {
    override val size: Int get() = data.size
    override fun get(index: Int): Any? = data[index]
    override fun set(index: Int, element: Any?): Any? { val old = data[index]; data[index] = element; return old }
    override fun add(index: Int, element: Any?) = throw UnsupportedOperationException()
    override fun removeAt(index: Int): Any? = throw UnsupportedOperationException()
}

internal fun ClassDefs.registerCollections() {
    define("java/util/Collection") {
        asInterface()
        implements("java/lang/Iterable")
        method("removeIf", "(Ljava/util/function/Predicate;)Z") {
            val predicate = r(1)
            var removed = false
            val it = vm.callVirtual(r(0), "iterator", "()Ljava/util/Iterator;")
            while (vm.callVirtual(it, "hasNext", "()Z") as Boolean) {
                val e = vm.callVirtual(it, "next", "()Ljava/lang/Object;")
                if (vm.callVirtual(predicate, "test", "(Ljava/lang/Object;)Z", e) as Boolean) {
                    vm.callVirtual(it, "remove", "()V"); removed = true
                }
            }
            ret(removed)
        }
        method("stream", "()Ljava/util/stream/Stream;") { throw dev.ide.vm.VmUnsupportedException("java.util.stream (Collection.stream)") }
    }
    define("java/util/SequencedCollection") { asInterface(); implements("java/util/Collection") }
    define("java/util/List") {
        asInterface()
        implements("java/util/SequencedCollection", "java/util/Collection")
        method("sort", "(Ljava/util/Comparator;)V") {
            val sorted = vm.elementsOf(r(0)).sortedWith(vm.comparatorFunction(r(1)))
            for (k in sorted.indices) vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", k, sorted[k])
        }
        method("replaceAll", "(Ljava/util/function/UnaryOperator;)V") {
            val items = vm.elementsOf(r(0))
            for (k in items.indices) {
                val v = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", items[k])
                vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", k, v)
            }
        }
        static("of", "()Ljava/util/List;") { retRef(vm.hostList(ArrayList(), mutable = false, resizable = false)) }
        for (n in 1..10) {
            static("of", "(" + "Ljava/lang/Object;".repeat(n) + ")Ljava/util/List;") {
                retRef(vm.hostList(MutableList(n) { r(it) }, mutable = false, resizable = false))
            }
        }
        static("of", "([Ljava/lang/Object;)Ljava/util/List;") {
            retRef(vm.hostList((r(0) as VmRefArray).data.toMutableList(), mutable = false, resizable = false))
        }
        static("copyOf", "(Ljava/util/Collection;)Ljava/util/List;") {
            retRef(vm.hostList(vm.elementsOf(r(0)).toMutableList(), mutable = false, resizable = false))
        }
    }
    define("java/util/Set") {
        asInterface()
        implements("java/util/Collection")
    }
    define("java/util/SortedSet") { asInterface(); implements("java/util/Set") }
    define("java/util/NavigableSet") { asInterface(); implements("java/util/SortedSet") }
    define("java/util/Queue") { asInterface(); implements("java/util/Collection") }
    define("java/util/Deque") { asInterface(); implements("java/util/Queue", "java/util/SequencedCollection") }
    define("java/util/Iterator") {
        asInterface()
        method("remove", "()V") { vm.throwVm("java/lang/UnsupportedOperationException", "remove") }
        method("forEachRemaining", "(Ljava/util/function/Consumer;)V") {
            while (vm.callVirtual(r(0), "hasNext", "()Z") as Boolean) {
                vm.callVirtual(r(1), "accept", "(Ljava/lang/Object;)V", vm.callVirtual(r(0), "next", "()Ljava/lang/Object;"))
            }
        }
    }
    define("java/util/ListIterator") { asInterface(); implements("java/util/Iterator") }
    define("java/util/Enumeration") { asInterface() }

    define("java/util/Comparator") {
        asInterface()
        method("reversed", "()Ljava/util/Comparator;") { retRef(vm.hostComparator(vm.comparatorFunction(r(0)).reversed())) }
        method("thenComparing", "(Ljava/util/Comparator;)Ljava/util/Comparator;") {
            retRef(vm.hostComparator(vm.comparatorFunction(r(0)).then(vm.comparatorFunction(r(1)))))
        }
        static("naturalOrder", "()Ljava/util/Comparator;") { retRef(vm.hostComparator(vm.comparatorFunction(null))) }
        static("reverseOrder", "()Ljava/util/Comparator;") { retRef(vm.hostComparator(vm.comparatorFunction(null).reversed())) }
    }
    define("java/util/HostComparator") {
        implements("java/util/Comparator")
        method("compare", "(Ljava/lang/Object;Ljava/lang/Object;)I") { ret((r(0) as HostComparator).comparator.compare(r(1), r(2))) }
    }

    define("java/util/HostIterator") {
        implements("java/util/ListIterator")
        method("hasNext", "()Z") { ret((r(0) as JIterator).hasNext()) }
        method("next", "()Ljava/lang/Object;") { retRef((r(0) as JIterator).next(vm)) }
        method("remove", "()V") { (r(0) as JIterator).remove(vm) }
        method("hasPrevious", "()Z") { ret((r(0) as JListIterator).hasPrevious()) }
        method("previous", "()Ljava/lang/Object;") { retRef((r(0) as JListIterator).previous(vm)) }
        method("nextIndex", "()I") { ret((r(0) as JListIterator).cursor) }
        method("previousIndex", "()I") { ret((r(0) as JListIterator).cursor - 1) }
        method("set", "(Ljava/lang/Object;)V") { (r(0) as JListIterator).set(vm, r(1)) }
        method("add", "(Ljava/lang/Object;)V") { (r(0) as JListIterator).add(vm, r(1)) }
    }

    define("java/util/AbstractCollection") {
        asAbstract()
        implements("java/util/Collection")
        ctor("()V") {}
        method("isEmpty", "()Z") { ret(vm.callVirtual(r(0), "size", "()I") as Int == 0) }
        method("contains", "(Ljava/lang/Object;)Z") { val o = r(1); ret(vm.elementsOf(r(0)).any { vm.vmEquals(o, it) }) }
        method("containsAll", "(Ljava/util/Collection;)Z") {
            val mine = vm.elementsOf(r(0))
            ret(vm.elementsOf(r(1)).all { o -> mine.any { vm.vmEquals(o, it) } })
        }
        method("toArray", "()[Ljava/lang/Object;") { retRef(vm.objectArray(vm.elementsOf(r(0)))) }
        method("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;") { retRef(vm.toTypedArrayLike(vm.elementsOf(r(0)), r(1))) }
        method("add", "(Ljava/lang/Object;)Z") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
        method("addAll", "(Ljava/util/Collection;)Z") {
            var changed = false
            for (e in vm.elementsOf(r(1))) if (vm.callVirtual(r(0), "add", "(Ljava/lang/Object;)Z", e) as Boolean) changed = true
            ret(changed)
        }
        method("remove", "(Ljava/lang/Object;)Z") { ret(removeMatching(vm, r(0), once = true) { vm.vmEquals(r(1), it) }) }
        method("removeAll", "(Ljava/util/Collection;)Z") {
            val other = vm.elementsOf(r(1))
            ret(removeMatching(vm, r(0), once = false) { e -> other.any { vm.vmEquals(it, e) } })
        }
        method("retainAll", "(Ljava/util/Collection;)Z") {
            val other = vm.elementsOf(r(1))
            ret(removeMatching(vm, r(0), once = false) { e -> other.none { vm.vmEquals(it, e) } })
        }
        method("clear", "()V") { removeMatching(vm, r(0), once = false) { true } }
        method("toString", "()Ljava/lang/String;") { retRef(vm.collectionToString(r(0)!!, vm.elementsOf(r(0)))) }
    }

    define("java/util/AbstractList") {
        superName = "java/util/AbstractCollection"
        asAbstract()
        implements("java/util/List")
        field("modCount", "I")
        ctor("()V") {}
        method("add", "(Ljava/lang/Object;)Z") {
            vm.callVirtual(r(0), "add", "(ILjava/lang/Object;)V", vm.callVirtual(r(0), "size", "()I"), r(1)); ret(true)
        }
        method("add", "(ILjava/lang/Object;)V") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
        method("set", "(ILjava/lang/Object;)Ljava/lang/Object;") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
        method("remove", "(I)Ljava/lang/Object;") { vm.throwVm("java/lang/UnsupportedOperationException", null) }
        method("indexOf", "(Ljava/lang/Object;)I") { val o = r(1); ret(vm.elementsOf(r(0)).indexOfFirst { vm.vmEquals(o, it) }) }
        method("lastIndexOf", "(Ljava/lang/Object;)I") { val o = r(1); ret(vm.elementsOf(r(0)).indexOfLast { vm.vmEquals(o, it) }) }
        method("clear", "()V") {
            val n = vm.callVirtual(r(0), "size", "()I") as Int
            for (k in n - 1 downTo 0) vm.callVirtual(r(0), "remove", "(I)Ljava/lang/Object;", k)
        }
        method("addAll", "(ILjava/util/Collection;)Z") {
            var at = i(1)
            val items = vm.elementsOf(r(2))
            for (e in items) vm.callVirtual(r(0), "add", "(ILjava/lang/Object;)V", at++, e)
            ret(items.isNotEmpty())
        }
        method("iterator", "()Ljava/util/Iterator;") { retRef(vm.virtualListIterator(r(0)!!, 0)) }
        method("listIterator", "()Ljava/util/ListIterator;") { retRef(vm.virtualListIterator(r(0)!!, 0)) }
        method("listIterator", "(I)Ljava/util/ListIterator;") { retRef(vm.virtualListIterator(r(0)!!, i(1))) }
        method("subList", "(II)Ljava/util/List;") {
            val all = vm.elementsOf(r(0))
            retRef(vm.hostList(all.subList(i(1), i(2)).toMutableList(), mutable = false, resizable = false))
        }
        method("equals", "(Ljava/lang/Object;)Z") { ret(vm.listEquals(r(0)!!, vm.elementsOf(r(0)), r(1))) }
        method("hashCode", "()I") { ret(vm.listHash(vm.elementsOf(r(0)))) }
    }

    define("java/util/AbstractSet") {
        superName = "java/util/AbstractCollection"
        asAbstract()
        implements("java/util/Set")
        ctor("()V") {}
        method("equals", "(Ljava/lang/Object;)Z") { ret(vm.setEquals(r(0)!!, r(1))) }
        method("hashCode", "()I") { ret(vm.elementsOf(r(0)).sumOf { vm.vmHashCode(it) }) }
    }
    define("java/util/AbstractQueue") {
        superName = "java/util/AbstractCollection"
        asAbstract()
        implements("java/util/Queue")
        ctor("()V") {}
    }
    define("java/util/AbstractSequentialList") {
        superName = "java/util/AbstractList"
        asAbstract()
        ctor("()V") {}
    }

    define("java/util/ArrayList") {
        superName = "java/util/AbstractList"
        implements("java/util/List", "java/util/RandomAccess", "java/lang/Cloneable", "java/io/Serializable")
        ctor("()V") { self().native = JList(ArrayList()) }
        ctor("(I)V") {
            if (i(1) < 0) vm.throwVm("java/lang/IllegalArgumentException", "Illegal Capacity: ${i(1)}")
            self().native = JList(ArrayList(i(1)))
        }
        ctor("(Ljava/util/Collection;)V") { self().native = JList(vm.elementsOf(r(1)).toMutableList()) }
        listMethods()
        method("clone", "()Ljava/lang/Object;") {
            val copy = VmObject(self().cls)
            copy.native = JList(ArrayList(jl().items))
            retRef(copy)
        }
        method("ensureCapacity", "(I)V") {}
        method("trimToSize", "()V") {}
    }
    define("java/util/HostList") {
        superName = "java/util/AbstractList"
        implements("java/util/List", "java/util/RandomAccess")
        listMethods()
    }
    for (deque in listOf("java/util/LinkedList", "java/util/ArrayDeque")) {
        define(deque) {
            superName = if (deque == "java/util/LinkedList") "java/util/AbstractSequentialList" else "java/util/AbstractCollection"
            implements("java/util/List", "java/util/Deque", "java/lang/Cloneable", "java/io/Serializable")
            ctor("()V") { self().native = JList(ArrayDeque()) }
            ctor("(I)V") { self().native = JList(ArrayDeque(i(1).coerceAtLeast(0))) }
            ctor("(Ljava/util/Collection;)V") { self().native = JList(ArrayDeque(vm.elementsOf(r(1)))) }
            listMethods()
            dequeMethods()
        }
    }
    for (vector in listOf("java/util/Vector", "java/util/Stack")) {
        define(vector) {
            superName = if (vector == "java/util/Stack") "java/util/Vector" else "java/util/AbstractList"
            implements("java/util/List", "java/util/RandomAccess", "java/lang/Cloneable", "java/io/Serializable")
            ctor("()V") { self().native = JList(ArrayList()) }
            ctor("(I)V") { self().native = JList(ArrayList()) }
            listMethods()
            method("push", "(Ljava/lang/Object;)Ljava/lang/Object;") { jl().items.add(r(1)); retRef(r(1)) }
            method("pop", "()Ljava/lang/Object;") {
                val items = jl().items
                if (items.isEmpty()) vm.throwVm("java/util/EmptyStackException", null)
                retRef(items.removeAt(items.size - 1))
            }
            method("peek", "()Ljava/lang/Object;") {
                val items = jl().items
                if (items.isEmpty()) vm.throwVm("java/util/EmptyStackException", null)
                retRef(items[items.size - 1])
            }
            method("empty", "()Z") { ret(jl().items.isEmpty()) }
            method("addElement", "(Ljava/lang/Object;)V") { jl().items.add(r(1)) }
            method("elementAt", "(I)Ljava/lang/Object;") { retRef(jl().items[i(1)]) }
            method("firstElement", "()Ljava/lang/Object;") { retRef(jl().items.first()) }
            method("lastElement", "()Ljava/lang/Object;") { retRef(jl().items.last()) }
        }
    }

    define("java/util/Objects") {
        static("equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z") { ret(vm.vmEquals(r(0), r(1))) }
        static("deepEquals", "(Ljava/lang/Object;Ljava/lang/Object;)Z") { ret(vm.vmEquals(r(0), r(1))) }
        static("hashCode", "(Ljava/lang/Object;)I") { ret(vm.vmHashCode(r(0))) }
        static("hash", "([Ljava/lang/Object;)I") {
            val a = r(0) as VmRefArray?
            ret(if (a == null) 0 else vm.listHash(a.data.asList()))
        }
        static("toString", "(Ljava/lang/Object;)Ljava/lang/String;") { retRef(vm.vmToString(r(0))) }
        static("toString", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;") { retRef(if (r(0) == null) r(1) else vm.vmToString(r(0))) }
        static("isNull", "(Ljava/lang/Object;)Z") { ret(r(0) == null) }
        static("nonNull", "(Ljava/lang/Object;)Z") { ret(r(0) != null) }
        static("requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;") { retRef(r(0) ?: vm.throwVm("java/lang/NullPointerException", null)) }
        static("requireNonNull", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;") {
            retRef(r(0) ?: vm.throwVm("java/lang/NullPointerException", r(1) as String?))
        }
        static("requireNonNull", "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;") {
            retRef(r(0) ?: vm.throwVm("java/lang/NullPointerException", r(1)?.let { vm.callVirtual(it, "get", "()Ljava/lang/Object;") as String? }))
        }
        static("requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") { retRef(r(0) ?: r(1)) }
        static("compare", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Comparator;)I") {
            ret(if (r(0) === r(1)) 0 else vm.comparatorFunction(r(2)).compare(r(0), r(1)))
        }
        static("checkIndex", "(II)I") {
            if (i(0) < 0 || i(0) >= i(1)) vm.throwVm("java/lang/IndexOutOfBoundsException", "Index ${i(0)} out of bounds for length ${i(1)}")
            ret(i(0))
        }
    }

    define("java/util/Arrays") { arraysMethods() }
    define("java/util/Collections") { collectionsMethods() }
}

internal class HostComparator(override val vmClass: VmClass, val comparator: Comparator<Any?>) : HostObject

internal fun Vm.hostComparator(c: Comparator<Any?>): HostComparator = HostComparator(loadClass("java/util/HostComparator"), c)

/** An iterator over an interpreted list, through its own `get`/`size`/`remove`. */
private fun Vm.virtualListIterator(list: Any, start: Int): JIterator {
    return object : JIterator() {
        override val vmClass: VmClass = loadClass("java/util/HostIterator")
        var cursor = start
        var last = -1
        override fun hasNext() = cursor < (callVirtual(list, "size", "()I") as Int)
        override fun next(vm: Vm): Any? {
            if (!hasNext()) vm.throwVm("java/util/NoSuchElementException", null)
            last = cursor
            return callVirtual(list, "get", "(I)Ljava/lang/Object;", cursor++)
        }
        override fun remove(vm: Vm) {
            if (last < 0) vm.throwVm("java/lang/IllegalStateException", null)
            callVirtual(list, "remove", "(I)Ljava/lang/Object;", last)
            if (last < cursor) cursor--
            last = -1
        }
    }
}

/** Removes elements of an interpreted collection through its own iterator. */
private fun removeMatching(vm: Vm, collection: Any?, once: Boolean, test: (Any?) -> Boolean): Boolean {
    val it = vm.callVirtual(collection, "iterator", "()Ljava/util/Iterator;")
    var removed = false
    while (vm.callVirtual(it, "hasNext", "()Z") as Boolean) {
        if (test(vm.callVirtual(it, "next", "()Ljava/lang/Object;"))) {
            vm.callVirtual(it, "remove", "()V")
            removed = true
            if (once) break
        }
    }
    return removed
}

internal fun Vm.setEquals(self: Any, other: Any?): Boolean {
    if (self === other) return true
    if (other == null || !isInstance(other, loadClass("java/util/Set"))) return false
    val mine = elementsOf(self)
    val theirs = elementsOf(other)
    if (mine.size != theirs.size) return false
    return theirs.all { o -> callVirtual(self, "contains", "(Ljava/lang/Object;)Z", o) as Boolean }
}

/** The `List` methods of a list the floor backs with a [JList]. */
private fun NativeClassBuilder.listMethods() {
    method("size", "()I") { ret(jl().items.size) }
    method("isEmpty", "()Z") { ret(jl().items.isEmpty()) }
    method("get", "(I)Ljava/lang/Object;") { retRef(jl().get(vm, i(1))) }
    method("set", "(ILjava/lang/Object;)Ljava/lang/Object;") {
        val l = jl(); l.checkMutable(vm); l.checkIndex(vm, i(1))
        retRef(l.items.set(i(1), r(2)))
    }
    method("add", "(Ljava/lang/Object;)Z") { val l = jl(); l.checkResizable(vm); l.items.add(r(1)); ret(true) }
    method("add", "(ILjava/lang/Object;)V") {
        val l = jl(); l.checkResizable(vm)
        if (i(1) < 0 || i(1) > l.items.size) vm.throwVm("java/lang/IndexOutOfBoundsException", "Index: ${i(1)}, Size: ${l.items.size}")
        l.items.add(i(1), r(2))
    }
    method("remove", "(I)Ljava/lang/Object;") { val l = jl(); l.checkResizable(vm); l.checkIndex(vm, i(1)); retRef(l.items.removeAt(i(1))) }
    method("remove", "(Ljava/lang/Object;)Z") {
        val l = jl(); l.checkResizable(vm)
        val k = l.items.indexOfFirst { vm.vmEquals(r(1), it) }
        if (k >= 0) l.items.removeAt(k)
        ret(k >= 0)
    }
    method("indexOf", "(Ljava/lang/Object;)I") { val o = r(1); ret(jl().items.indexOfFirst { vm.vmEquals(o, it) }) }
    method("lastIndexOf", "(Ljava/lang/Object;)I") { val o = r(1); ret(jl().items.indexOfLast { vm.vmEquals(o, it) }) }
    method("contains", "(Ljava/lang/Object;)Z") { val o = r(1); ret(jl().items.any { vm.vmEquals(o, it) }) }
    method("containsAll", "(Ljava/util/Collection;)Z") {
        val items = jl().items
        ret(vm.elementsOf(r(1)).all { o -> items.any { vm.vmEquals(o, it) } })
    }
    method("clear", "()V") { val l = jl(); l.checkResizable(vm); l.items.clear() }
    method("addAll", "(Ljava/util/Collection;)Z") {
        val l = jl(); l.checkResizable(vm)
        val add = vm.elementsOf(r(1)); l.items.addAll(add); ret(add.isNotEmpty())
    }
    method("addAll", "(ILjava/util/Collection;)Z") {
        val l = jl(); l.checkResizable(vm)
        val add = vm.elementsOf(r(2)); l.items.addAll(i(1), add); ret(add.isNotEmpty())
    }
    method("removeAll", "(Ljava/util/Collection;)Z") {
        val l = jl(); l.checkResizable(vm)
        val other = vm.elementsOf(r(1))
        ret(l.items.removeAll { e -> other.any { vm.vmEquals(it, e) } })
    }
    method("retainAll", "(Ljava/util/Collection;)Z") {
        val l = jl(); l.checkResizable(vm)
        val other = vm.elementsOf(r(1))
        ret(l.items.removeAll { e -> other.none { vm.vmEquals(it, e) } })
    }
    method("removeIf", "(Ljava/util/function/Predicate;)Z") {
        val l = jl(); l.checkResizable(vm)
        val p = r(1)
        val keep = l.items.filterNot { vm.callVirtual(p, "test", "(Ljava/lang/Object;)Z", it) as Boolean }
        val removed = keep.size != l.items.size
        l.items.clear(); l.items.addAll(keep)
        ret(removed)
    }
    method("iterator", "()Ljava/util/Iterator;") { retRef(JListIterator(vm.loadClass("java/util/HostIterator"), jl(), 0)) }
    method("listIterator", "()Ljava/util/ListIterator;") { retRef(JListIterator(vm.loadClass("java/util/HostIterator"), jl(), 0)) }
    method("listIterator", "(I)Ljava/util/ListIterator;") { retRef(JListIterator(vm.loadClass("java/util/HostIterator"), jl(), i(1))) }
    method("toArray", "()[Ljava/lang/Object;") { retRef(vm.objectArray(jl().items)) }
    method("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;") { retRef(vm.toTypedArrayLike(jl().items, r(1))) }
    method("subList", "(II)Ljava/util/List;") {
        val l = jl()
        if (i(1) < 0 || i(2) > l.items.size || i(1) > i(2)) vm.throwVm("java/lang/IndexOutOfBoundsException", "fromIndex ${i(1)}, toIndex ${i(2)}")
        retRef(HostList(vm.loadClass("java/util/HostList"), JList(l.items.subList(i(1), i(2)), l.mutable, l.resizable)))
    }
    method("sort", "(Ljava/util/Comparator;)V") { val l = jl(); l.checkMutable(vm); l.items.sortWith(vm.comparatorFunction(r(1))) }
    method("forEach", "(Ljava/util/function/Consumer;)V") {
        val a = r(1)
        for (e in jl().items.toList()) vm.callVirtual(a, "accept", "(Ljava/lang/Object;)V", e)
    }
    method("replaceAll", "(Ljava/util/function/UnaryOperator;)V") {
        val l = jl(); l.checkMutable(vm)
        for (k in l.items.indices) l.items[k] = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", l.items[k])
    }
    method("equals", "(Ljava/lang/Object;)Z") { ret(vm.listEquals(r(0)!!, jl().items, r(1))) }
    method("hashCode", "()I") { ret(vm.listHash(jl().items)) }
    method("toString", "()Ljava/lang/String;") { retRef(vm.collectionToString(r(0)!!, jl().items)) }
    method("getFirst", "()Ljava/lang/Object;") { val l = jl().items; if (l.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(l.first()) }
    method("getLast", "()Ljava/lang/Object;") { val l = jl().items; if (l.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(l.last()) }
    method("removeFirst", "()Ljava/lang/Object;") { val l = jl(); l.checkResizable(vm); if (l.items.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(l.items.removeAt(0)) }
    method("removeLast", "()Ljava/lang/Object;") { val l = jl(); l.checkResizable(vm); if (l.items.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); retRef(l.items.removeAt(l.items.size - 1)) }
    method("addFirst", "(Ljava/lang/Object;)V") { val l = jl(); l.checkResizable(vm); l.items.add(0, r(1)) }
    method("addLast", "(Ljava/lang/Object;)V") { val l = jl(); l.checkResizable(vm); l.items.add(r(1)) }
}

private fun NativeClassBuilder.dequeMethods() {
    fun Call.items() = jl().items
    fun Call.first(): Any? { val l = items(); if (l.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); return l.first() }
    fun Call.last(): Any? { val l = items(); if (l.isEmpty()) vm.throwVm("java/util/NoSuchElementException", null); return l.last() }
    method("offer", "(Ljava/lang/Object;)Z") { items().add(r(1)); ret(true) }
    method("offerFirst", "(Ljava/lang/Object;)Z") { items().add(0, r(1)); ret(true) }
    method("offerLast", "(Ljava/lang/Object;)Z") { items().add(r(1)); ret(true) }
    method("push", "(Ljava/lang/Object;)V") { items().add(0, r(1)) }
    method("pop", "()Ljava/lang/Object;") { first(); retRef(items().removeAt(0)) }
    method("poll", "()Ljava/lang/Object;") { val l = items(); retRef(if (l.isEmpty()) null else l.removeAt(0)) }
    method("pollFirst", "()Ljava/lang/Object;") { val l = items(); retRef(if (l.isEmpty()) null else l.removeAt(0)) }
    method("pollLast", "()Ljava/lang/Object;") { val l = items(); retRef(if (l.isEmpty()) null else l.removeAt(l.size - 1)) }
    method("peek", "()Ljava/lang/Object;") { retRef(items().firstOrNull()) }
    method("peekFirst", "()Ljava/lang/Object;") { retRef(items().firstOrNull()) }
    method("peekLast", "()Ljava/lang/Object;") { retRef(items().lastOrNull()) }
    method("element", "()Ljava/lang/Object;") { retRef(first()) }
    method("remove", "()Ljava/lang/Object;") { first(); retRef(items().removeAt(0)) }
    method("descendingIterator", "()Ljava/util/Iterator;") { retRef(vm.hostIterator(items().reversed().iterator())) }
    method("removeFirstOccurrence", "(Ljava/lang/Object;)Z") {
        val l = items(); val k = l.indexOfFirst { vm.vmEquals(r(1), it) }; if (k >= 0) l.removeAt(k); ret(k >= 0)
    }
}

private fun NativeClassBuilder.arraysMethods() {
    static("asList", "([Ljava/lang/Object;)Ljava/util/List;") {
        val a = r(0) as VmRefArray
        retRef(vm.hostList(ArrayBackedList(a.data), mutable = true, resizable = false))
    }
    for (t in listOf("I", "J", "C", "B", "S", "F", "D", "Z", "Ljava/lang/Object;")) {
        val arr = "[$t"
        static("fill", "($arr$t)V") { fillArray(r(0), 0, -1, this, 1) }
        static("fill", "(${arr}II$t)V") { fillArray(r(0), i(1), i(2), this, 3) }
        static("copyOf", "(${arr}I)$arr") { retRef(copyArray(vm, r(0)!!, 0, i(1), true)) }
        static("copyOfRange", "(${arr}II)$arr") { retRef(copyArray(vm, r(0)!!, i(1), i(2), false)) }
        static("equals", "($arr$arr)Z") { ret(arraysEqual(vm, r(0), r(1))) }
        static("hashCode", "($arr)I") { ret(arrayHash(vm, r(0))) }
        static("toString", "($arr)Ljava/lang/String;") { retRef(arrayToString(vm, r(0))) }
        if (t != "Z" && t != "Ljava/lang/Object;") {
            static("sort", "($arr)V") { sortPrimitive(r(0)!!, 0, -1) }
            static("sort", "(${arr}II)V") { sortPrimitive(r(0)!!, i(1), i(2)) }
        }
    }
    static("copyOf", "([Ljava/lang/Object;ILjava/lang/Class;)[Ljava/lang/Object;") {
        val src = r(0) as VmRefArray
        val type = ((r(2) as VmObject).native as VmClass)
        retRef(VmRefArray(type, Array(i(1)) { if (it < src.data.size) src.data[it] else null }))
    }
    static("deepEquals", "([Ljava/lang/Object;[Ljava/lang/Object;)Z") { ret(arraysEqual(vm, r(0), r(1))) }
    static("deepHashCode", "([Ljava/lang/Object;)I") { ret(arrayHash(vm, r(0))) }
    static("deepToString", "([Ljava/lang/Object;)Ljava/lang/String;") { retRef(arrayToString(vm, r(0))) }
    static("sort", "([Ljava/lang/Object;)V") { (r(0) as VmRefArray).data.sortWith(vm.comparatorFunction(null)) }
    static("sort", "([Ljava/lang/Object;II)V") { (r(0) as VmRefArray).data.sortWith(vm.comparatorFunction(null), i(1), i(2)) }
    static("sort", "([Ljava/lang/Object;Ljava/util/Comparator;)V") { (r(0) as VmRefArray).data.sortWith(vm.comparatorFunction(r(1))) }
    static("sort", "([Ljava/lang/Object;IILjava/util/Comparator;)V") { (r(0) as VmRefArray).data.sortWith(vm.comparatorFunction(r(3)), i(1), i(2)) }
    static("binarySearch", "([II)I") { ret((r(0) as IntArray).asList().binarySearch(i(1))) }
    static("binarySearch", "([IIII)I") { ret((r(0) as IntArray).asList().binarySearch(i(3), i(1), i(2))) }
    static("binarySearch", "([JJ)I") { ret((r(0) as LongArray).asList().binarySearch(l(1))) }
    static("binarySearch", "([Ljava/lang/Object;Ljava/lang/Object;)I") {
        ret((r(0) as VmRefArray).data.asList().binarySearch(r(1), vm.comparatorFunction(null)))
    }
    static("binarySearch", "([Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Comparator;)I") {
        ret((r(0) as VmRefArray).data.asList().binarySearch(r(1), vm.comparatorFunction(r(2))))
    }
}

private fun fillArray(a: Any?, from: Int, to: Int, call: Call, valueWord: Int) {
    when (a) {
        is IntArray -> a.fill(call.i(valueWord), from, if (to < 0) a.size else to)
        is LongArray -> a.fill(call.l(valueWord), from, if (to < 0) a.size else to)
        is CharArray -> a.fill(call.c(valueWord), from, if (to < 0) a.size else to)
        is ByteArray -> a.fill(call.i(valueWord).toByte(), from, if (to < 0) a.size else to)
        is ShortArray -> a.fill(call.i(valueWord).toShort(), from, if (to < 0) a.size else to)
        is FloatArray -> a.fill(call.f(valueWord), from, if (to < 0) a.size else to)
        is DoubleArray -> a.fill(call.d(valueWord), from, if (to < 0) a.size else to)
        is BooleanArray -> a.fill(call.z(valueWord), from, if (to < 0) a.size else to)
        is VmRefArray -> a.data.fill(call.r(valueWord), from, if (to < 0) a.data.size else to)
        null -> call.vm.throwVm("java/lang/NullPointerException", "fill")
    }
}

private fun copyArray(vm: Vm, a: Any, from: Int, toOrLength: Int, isLength: Boolean): Any {
    val to = if (isLength) from + toOrLength else toOrLength
    if (to - from < 0) vm.throwVm(if (isLength) "java/lang/NegativeArraySizeException" else "java/lang/IllegalArgumentException", "${to - from}")
    fun <T> range(size: Int, make: (Int) -> T, copy: (T, Int) -> Unit): T { val out = make(to - from); copy(out, minOf(to, size)); return out }
    return when (a) {
        is IntArray -> range(a.size, ::IntArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is LongArray -> range(a.size, ::LongArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is CharArray -> range(a.size, ::CharArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is ByteArray -> range(a.size, ::ByteArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is ShortArray -> range(a.size, ::ShortArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is FloatArray -> range(a.size, ::FloatArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is DoubleArray -> range(a.size, ::DoubleArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is BooleanArray -> range(a.size, ::BooleanArray) { o, end -> if (end > from) a.copyInto(o, 0, from, end) }
        is VmRefArray -> {
            val out = arrayOfNulls<Any?>(to - from)
            val end = minOf(to, a.data.size)
            if (end > from) a.data.copyInto(out, 0, from, end)
            VmRefArray(a.cls, out)
        }
        else -> vm.throwVm("java/lang/IllegalArgumentException", "not an array")
    }
}

private fun sortPrimitive(a: Any, from: Int, to: Int) {
    when (a) {
        is IntArray -> a.sort(from, if (to < 0) a.size else to)
        is LongArray -> a.sort(from, if (to < 0) a.size else to)
        is CharArray -> a.sort(from, if (to < 0) a.size else to)
        is ByteArray -> a.sort(from, if (to < 0) a.size else to)
        is ShortArray -> a.sort(from, if (to < 0) a.size else to)
        is FloatArray -> a.sort(from, if (to < 0) a.size else to)
        is DoubleArray -> a.sort(from, if (to < 0) a.size else to)
    }
}

private fun arrayElements(a: Any): List<Any?> = when (a) {
    is IntArray -> a.toList()
    is LongArray -> a.toList()
    is CharArray -> a.toList()
    is ByteArray -> a.toList()
    is ShortArray -> a.toList()
    is FloatArray -> a.toList()
    is DoubleArray -> a.toList()
    is BooleanArray -> a.toList()
    is VmRefArray -> a.data.asList()
    else -> emptyList()
}

private fun arraysEqual(vm: Vm, a: Any?, b: Any?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    val x = arrayElements(a)
    val y = arrayElements(b)
    if (x.size != y.size) return false
    for (k in x.indices) if (!vm.vmEquals(x[k], y[k])) return false
    return true
}

private fun arrayHash(vm: Vm, a: Any?): Int = if (a == null) 0 else vm.listHash(arrayElements(a))

private fun arrayToString(vm: Vm, a: Any?): String =
    if (a == null) "null" else arrayElements(a).joinToString(", ", "[", "]") { vm.vmToString(it) }

private fun NativeClassBuilder.collectionsMethods() {
    staticField("EMPTY_LIST", "Ljava/util/List;")
    staticField("EMPTY_SET", "Ljava/util/Set;")
    staticField("EMPTY_MAP", "Ljava/util/Map;")
    onInit { cls ->
        cls.setStaticRef("EMPTY_LIST", "Ljava/util/List;", vm.hostList(ArrayList(), false, false))
        cls.setStaticRef("EMPTY_SET", "Ljava/util/Set;", vm.emptyHostSet())
        cls.setStaticRef("EMPTY_MAP", "Ljava/util/Map;", vm.emptyHostMap())
    }
    static("emptyList", "()Ljava/util/List;") { retRef(vm.hostList(ArrayList(), false, false)) }
    static("emptySet", "()Ljava/util/Set;") { retRef(vm.emptyHostSet()) }
    static("emptyMap", "()Ljava/util/Map;") { retRef(vm.emptyHostMap()) }
    static("emptyIterator", "()Ljava/util/Iterator;") { retRef(vm.hostIterator(emptyList<Any?>().iterator())) }
    static("singletonList", "(Ljava/lang/Object;)Ljava/util/List;") { retRef(vm.hostList(mutableListOf(r(0)), false, false)) }
    static("singleton", "(Ljava/lang/Object;)Ljava/util/Set;") { retRef(vm.hostSet(listOf(r(0)), mutable = false)) }
    static("singletonMap", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;") { retRef(vm.hostMap(listOf(r(0) to r(1)), mutable = false)) }
    for (kind in listOf("List", "Set", "Map", "Collection", "SortedSet", "SortedMap", "NavigableSet", "NavigableMap")) {
        static("unmodifiable$kind", "(Ljava/util/$kind;)Ljava/util/$kind;") { retRef(r(0)) }
        static("synchronized$kind", "(Ljava/util/$kind;)Ljava/util/$kind;") { retRef(r(0)) }
    }
    static("sort", "(Ljava/util/List;)V") { vm.callVirtual(r(0), "sort", "(Ljava/util/Comparator;)V", null) }
    static("sort", "(Ljava/util/List;Ljava/util/Comparator;)V") { vm.callVirtual(r(0), "sort", "(Ljava/util/Comparator;)V", r(1)) }
    static("reverse", "(Ljava/util/List;)V") {
        val items = vm.elementsOf(r(0)).reversed()
        for (k in items.indices) vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", k, items[k])
    }
    static("swap", "(Ljava/util/List;II)V") {
        val a = vm.callVirtual(r(0), "get", "(I)Ljava/lang/Object;", i(1))
        val b = vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", i(2), a)
        vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", i(1), b)
    }
    static("addAll", "(Ljava/util/Collection;[Ljava/lang/Object;)Z") {
        var changed = false
        for (e in (r(1) as VmRefArray).data) if (vm.callVirtual(r(0), "add", "(Ljava/lang/Object;)Z", e) as Boolean) changed = true
        ret(changed)
    }
    static("nCopies", "(ILjava/lang/Object;)Ljava/util/List;") { retRef(vm.hostList(MutableList(i(0)) { r(1) }, false, false)) }
    static("reverseOrder", "()Ljava/util/Comparator;") { retRef(vm.hostComparator(vm.comparatorFunction(null).reversed())) }
    static("reverseOrder", "(Ljava/util/Comparator;)Ljava/util/Comparator;") { retRef(vm.hostComparator(vm.comparatorFunction(r(0)).reversed())) }
    static("max", "(Ljava/util/Collection;)Ljava/lang/Object;") { retRef(vm.elementsOf(r(0)).maxWithOrNull(vm.comparatorFunction(null)) ?: vm.throwVm("java/util/NoSuchElementException", null)) }
    static("min", "(Ljava/util/Collection;)Ljava/lang/Object;") { retRef(vm.elementsOf(r(0)).minWithOrNull(vm.comparatorFunction(null)) ?: vm.throwVm("java/util/NoSuchElementException", null)) }
    static("frequency", "(Ljava/util/Collection;Ljava/lang/Object;)I") { ret(vm.elementsOf(r(0)).count { vm.vmEquals(r(1), it) }) }
    static("shuffle", "(Ljava/util/List;)V") {
        val items = vm.elementsOf(r(0)).shuffled()
        for (k in items.indices) vm.callVirtual(r(0), "set", "(ILjava/lang/Object;)Ljava/lang/Object;", k, items[k])
    }
}
