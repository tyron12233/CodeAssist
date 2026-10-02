package dev.ide.vm

/** Helpers the JDK floor shares: allocation, iteration over any collection, the shapes of names. */

internal fun Vm.newVmObject(name: String): VmObject {
    val cls = loadClass(name)
    ensureInitialized(cls)
    return VmObject(cls)
}

/** One shared instance of a floor class per VM (`Runtime.getRuntime()`). */
internal fun Vm.singleton(name: String): VmObject = singletons.getOrPut(name) { newVmObject(name) }

internal fun Vm.mainThreadObject(): VmObject = singleton("java/lang/Thread")

internal fun Vm.nextThreadNumber(): Int = threadNumber++

internal fun Vm.printStream(): VmObject {
    val o = newVmObject("java/io/PrintStream")
    o.native = stdout
    return o
}

internal fun VmClass.setStaticRef(name: String, descriptor: String, value: Any?) {
    val f = fields["$name:$descriptor"] ?: error("no static $name in ${this.name}")
    staticRefs[f.slot] = value
}

internal fun VmClass.setStaticPrim(name: String, descriptor: String, value: Long) {
    val f = fields["$name:$descriptor"] ?: error("no static $name in ${this.name}")
    staticPrims[f.slot] = value
}

/** `Object.clone()`: a shallow copy of an array or of a `Cloneable` object. */
internal fun Vm.cloneValue(o: Any): Any = when (o) {
    is VmRefArray -> VmRefArray(o.cls, o.data.copyOf())
    is IntArray -> o.copyOf()
    is LongArray -> o.copyOf()
    is CharArray -> o.copyOf()
    is ByteArray -> o.copyOf()
    is FloatArray -> o.copyOf()
    is DoubleArray -> o.copyOf()
    is BooleanArray -> o.copyOf()
    is ShortArray -> o.copyOf()
    is VmObject -> {
        if (!isAssignable(o.cls, loadClass("java/lang/Cloneable"))) throwVm("java/lang/CloneNotSupportedException", o.cls.javaName)
        val copy = VmObject(o.cls)
        o.prims.copyInto(copy.prims)
        o.refs.copyInto(copy.refs)
        copy.native = o.native
        copy
    }
    else -> throwVm("java/lang/CloneNotSupportedException", classOf(o).javaName)
}

/** `Class.getSimpleName()`. */
internal fun VmClass.simpleName(): String {
    if (isPrimitive) return name
    if (isArray) return componentClass!!.simpleName() + "[]"
    parsed?.let { p -> if (p.isNested) return p.innerName ?: "" }
    val simple = name.substringAfterLast('/')
    val inner = simple.substringAfterLast('$')
    return if (inner.isNotEmpty() && inner[0].isDigit()) "" else inner
}

/** `Class.getTypeName()`: `int[]` rather than `[I`. */
internal fun VmClass.typeName(): String = if (isArray) componentClass!!.typeName() + "[]" else javaName

/** The constants of an enum class, from its `values()` method, or null if it is not an enum. */
internal fun Vm.enumConstants(cls: VmClass): VmRefArray? {
    if (cls.superClass?.name != "java/lang/Enum") return null
    ensureInitialized(cls)
    val values = cls.methods["values()[L${cls.name};"] ?: return null
    return callMethod(values) as VmRefArray
}

/** The constant of enum class [cls] called [name], or null. */
fun Vm.enumConstantNamed(cls: VmClass, name: String): Any? =
    enumConstants(cls)?.data?.firstOrNull { (it as VmObject).refs[0] == name }

/**
 * Runs [action] on each element of a collection, whatever it is: a floor collection is walked directly, an
 * interpreted one through its own `iterator()`.
 */
internal fun Vm.forEachElement(collection: Any?, action: (Any?) -> Unit) {
    if (collection == null) throwNpe("iterating null")
    val direct = directElements(collection)
    if (direct != null) {
        for (e in direct) action(e)
        return
    }
    val it = callVirtual(collection, "iterator", "()Ljava/util/Iterator;")
    while (callVirtual(it, "hasNext", "()Z") as Boolean) action(callVirtual(it, "next", "()Ljava/lang/Object;"))
}

/** The elements of [collection] as a snapshot list when the floor holds them itself, else null. */
internal fun Vm.directElements(collection: Any): List<Any?>? {
    val backing = (collection as? VmObject)?.native ?: collection
    return when (backing) {
        is JList -> backing.items.toList()
        is JSorted -> if (backing.isSet) backing.keys.toList() else null
        is dev.ide.vm.jdk.HostList -> backing.list.items.toList()
        is dev.ide.vm.jdk.HostMap -> if (backing.vmClass.isSetLike) backing.map.keys() else null
        is JHashMapView -> backing.snapshot()
        is JHashMap -> if (collection is VmObject && collection.cls.isSetLike) backing.keys() else null
        else -> null
    }
}

/** Collects the elements of any collection into a list. */
internal fun Vm.elementsOf(collection: Any?): List<Any?> {
    val out = ArrayList<Any?>()
    forEachElement(collection) { out.add(it) }
    return out
}

internal val VmClass.isSetLike: Boolean get() = vm.isAssignable(this, vm.loadClass("java/util/Set"))

/** `[a, b, c]`, as `AbstractCollection.toString` prints. */
internal fun Vm.collectionToString(self: Any, elements: List<Any?>): String =
    elements.joinToString(", ", "[", "]") { if (it === self) "(this Collection)" else vmToString(it) }

internal fun VmClass.declaredFieldNamed(name: String): VmField? = fields.values.firstOrNull { it.name == name && !it.isStatic }

internal fun Vm.identityFunction(): VmObject = singleton("java/util/HostIdentity")
