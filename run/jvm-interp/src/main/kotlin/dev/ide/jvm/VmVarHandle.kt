package dev.ide.jvm

import java.util.concurrent.atomic.AtomicReference

/**
 * What interpreted code holds in place of a `java.lang.invoke.VarHandle` over an interpreted field or over an
 * array's elements.
 *
 * A real `VarHandle` addresses memory the platform laid out, which an interpreted object's fields are not: they
 * live in the interpreter's own storage, as do the elements of an array of references (a [VmArray]).
 * `VarHandle` cannot be subclassed either, so this is not one. It never needs to be: the program only ever
 * calls a var handle's access methods, which arrive at the VM as a call by name on whatever object the field
 * held, and [invoke] answers them. (The same arrangement as [VmServiceLoader].)
 *
 * Every access mode is the strongest one: plain, opaque, acquire/release and volatile all read and write the
 * current value, and the compound operations (compare-and-set, get-and-add, ...) are atomic with respect to
 * each other through a lock on the object, the array or the static cell. That is stronger than asked for, never
 * weaker.
 */
class VmVarHandle internal constructor(
    private val vm: Vm,
    /** The variable's descriptor (`I`, `Ljava/lang/String;`). */
    private val descriptor: String,
    private val kind: Kind,
    /** The field's name, for an instance field. */
    private val field: String = "",
    private val volatile: Boolean = false,
    /** The cell, for a static field. */
    private val cell: Any? = null,
    private val description: String,
) {
    internal enum class Kind(val coordinates: Int) { INSTANCE(1), STATIC(0), ARRAY(2) }

    /** One addressed variable: where to read and write it, and what to lock for a compound operation. */
    private abstract class Slot(val lock: Any) {
        abstract fun get(): Any?
        abstract fun set(value: Any?)
    }

    private fun slot(args: List<Any?>): Slot = when (kind) {
        Kind.STATIC -> {
            val c = cell as StaticCell
            object : Slot(c) {
                @Suppress("UNCHECKED_CAST")
                override fun get(): Any? = if (c.kind == FieldKinds.VOLATILE) (c.ref as AtomicReference<Any?>).get() else c.boxed()
                @Suppress("UNCHECKED_CAST")
                override fun set(value: Any?) {
                    if (c.kind == FieldKinds.VOLATILE) (c.ref as AtomicReference<Any?>).set(value) else c.store(value)
                }
            }
        }
        Kind.INSTANCE -> {
            val target = when (val a = args[0]) {
                null -> throw VmException(NullPointerException())
                is VmObject -> a
                is VmPeer -> a.vmObject() as VmObject
                else -> throw VmException(ClassCastException("${a.javaClass.name} has no field $description"))
            }
            val fields = target.fields
            object : Slot(target) {
                @Suppress("UNCHECKED_CAST")
                override fun get(): Any? = if (volatile) (fields[field] as AtomicReference<Any?>).get() else fields[field]
                @Suppress("UNCHECKED_CAST")
                override fun set(value: Any?) {
                    if (volatile) (fields[field] as AtomicReference<Any?>).set(value) else fields[field] = value
                }
            }
        }
        Kind.ARRAY -> {
            val index = args[1] as Int
            when (val array = args[0]) {
                null -> throw VmException(NullPointerException())
                // An array of references is the interpreter's own.
                is VmArray -> {
                    checkIndex(index, array.length)
                    object : Slot(array) {
                        override fun get(): Any? = array.data[index]
                        override fun set(value: Any?) { array.data[index] = value }
                    }
                }
                // An array of primitives (or of a platform type) is a real one, holding real values.
                else -> {
                    if (!array.javaClass.isArray) throw VmException(ClassCastException("not an array: ${array.javaClass.name}"))
                    checkIndex(index, java.lang.reflect.Array.getLength(array))
                    object : Slot(array) {
                        override fun get(): Any? = Marshalling.realToVm(java.lang.reflect.Array.get(array, index), descriptor)
                        override fun set(value: Any?) = java.lang.reflect.Array.set(array, index, Marshalling.toRealArg(vm.toReal(value), descriptor))
                    }
                }
            }
        }
    }

    private fun checkIndex(index: Int, length: Int) {
        if (index < 0 || index >= length) {
            throw VmException(ArrayIndexOutOfBoundsException("Index $index out of bounds for length $length"))
        }
    }

    /**
     * Runs access method [name] with [args] in the interpreter's own value form (the coordinates first, then the
     * operands), returning the result in that form too. [callDescriptor] is the call site's, which says whether
     * a result is wanted.
     */
    internal fun invoke(name: String, callDescriptor: String, args: List<Any?>): Any? {
        when (name) {
            "withInvokeExactBehavior", "withInvokeBehavior" -> return this
            "hasInvokeExactBehavior" -> return 0
            "isAccessModeSupported" -> return 1
            "varType" -> return vm.classForDescriptor(descriptor)
            "toString" -> return "VarHandle[$description]"
            "hashCode" -> return System.identityHashCode(this)
            "equals" -> return if (args.getOrNull(0) === this) 1 else 0
        }
        val op = name.removeSuffix("Plain").removeSuffix("Acquire").removeSuffix("Release")
            .removeSuffix("Volatile").removeSuffix("Opaque")
        val slot = slot(args)
        val operands = args.subList(kind.coordinates, args.size)
        val result: Any? = when (op) {
            "get" -> slot.get()
            "set" -> { slot.set(operands[0]); null }
            "compareAndSet", "weakCompareAndSet" -> synchronized(slot.lock) {
                if (same(slot.get(), operands[0])) { slot.set(operands[1]); 1 } else 0
            }
            "compareAndExchange" -> synchronized(slot.lock) {
                val witness = slot.get()
                if (same(witness, operands[0])) slot.set(operands[1])
                witness
            }
            "getAndSet" -> synchronized(slot.lock) { slot.get().also { slot.set(operands[0]) } }
            "getAndAdd" -> synchronized(slot.lock) { slot.get().also { slot.set(added(it, operands[0])) } }
            "getAndBitwiseOr", "getAndBitwiseAnd", "getAndBitwiseXor" ->
                synchronized(slot.lock) { slot.get().also { slot.set(combined(op, it, operands[0])) } }
            else -> throw VmUnsupportedException("VarHandle.$name is not supported in the interpreter")
        }
        return if (Descriptors.returnType(callDescriptor) == "V") null else result
    }

    /** Whether the variable holds [expected]: by value for a primitive (a float by its bits, as `equals` on the
     *  wrapper compares), by identity for a reference. */
    private fun same(current: Any?, expected: Any?): Boolean =
        if (descriptor.length == 1) current == expected else current === expected

    private fun added(current: Any?, delta: Any?): Any? = when (descriptor) {
        "J" -> (current as Long) + (delta as Number).toLong()
        "F" -> (current as Float) + (delta as Number).toFloat()
        "D" -> (current as Double) + (delta as Number).toDouble()
        "I" -> (current as Int) + (delta as Int)
        "B" -> ((current as Int) + (delta as Int)).toByte().toInt()
        "S" -> ((current as Int) + (delta as Int)).toShort().toInt()
        "C" -> ((current as Int) + (delta as Int)) and 0xFFFF
        else -> throw VmException(UnsupportedOperationException("getAndAdd on a $descriptor variable"))
    }

    private fun combined(op: String, current: Any?, mask: Any?): Any? = when (descriptor) {
        "J" -> (current as Long).let { c -> (mask as Long).let { m -> if (op.endsWith("Or")) c or m else if (op.endsWith("And")) c and m else c xor m } }
        "I", "B", "S", "C", "Z" -> (current as Int).let { c -> (mask as Int).let { m -> if (op.endsWith("Or")) c or m else if (op.endsWith("And")) c and m else c xor m } }
        else -> throw VmException(UnsupportedOperationException("$op on a $descriptor variable"))
    }

    override fun toString(): String = "VarHandle[$description]"
}
