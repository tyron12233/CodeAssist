package dev.ide.jvm

/**
 * What interpreted code holds in place of an `AtomicIntegerFieldUpdater`, `AtomicLongFieldUpdater` or
 * `AtomicReferenceFieldUpdater` over an interpreted class's field. The real ones look the field up on the
 * class they are given, and the class standing for an interpreted type declares none
 * (`NoSuchFieldException: count`). Like [VmVarHandle], which does the work, this is not the real type: the
 * program only calls the updater's methods, and those arrive here by name.
 */
class VmFieldUpdater internal constructor(
    private val vm: Vm,
    private val handle: VmVarHandle,
    /** `I`, `J`, or `L` for a reference: which functional interface an update function is. */
    private val type: Char,
    private val description: String,
) {
    private fun access(op: String, vararg args: Any?): Any? = handle.invoke(op, VALUE, args.asList())

    /** Runs updater method [name] with [args] (the object first) in the interpreter's value form. */
    internal fun invoke(name: String, callDescriptor: String, args: List<Any?>): Any? {
        val target = args.getOrNull(0)
        val result: Any? = when (name) {
            "get" -> access("get", target)
            "set", "lazySet" -> { access("set", target, args[1]); null }
            "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile" ->
                access("compareAndSet", target, args[1], args[2])
            "getAndSet" -> access("getAndSet", target, args[1])
            "getAndAdd" -> access("getAndAdd", target, args[1])
            "getAndIncrement" -> access("getAndAdd", target, one(1))
            "getAndDecrement" -> access("getAndAdd", target, one(-1))
            "addAndGet" -> plus(access("getAndAdd", target, args[1]), args[1])
            "incrementAndGet" -> plus(access("getAndAdd", target, one(1)), one(1))
            "decrementAndGet" -> plus(access("getAndAdd", target, one(-1)), one(-1))
            "getAndUpdate", "updateAndGet" -> update(target, returnNew = name == "updateAndGet") { current -> apply(args[1], current) }
            "getAndAccumulate", "accumulateAndGet" ->
                update(target, returnNew = name == "accumulateAndGet") { current -> apply(args[2], current, args[1]) }
            "toString" -> "FieldUpdater[$description]"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> if (args.getOrNull(0) === this) 1 else 0
            else -> throw VmUnsupportedException("${description}: field updater method $name is not supported in the interpreter")
        }
        return if (Descriptors.returnType(callDescriptor) == "V") null else result
    }

    /** The compare-and-set loop the real updaters run: the function may be called more than once. */
    private inline fun update(target: Any?, returnNew: Boolean, next: (Any?) -> Any?): Any? {
        while (true) {
            val current = access("get", target)
            val updated = next(current)
            if (access("compareAndSet", target, current, updated) == 1) return if (returnNew) updated else current
        }
    }

    /** Calls the program's update function: an `IntUnaryOperator`/`IntBinaryOperator`, the long pair, or a
     *  `UnaryOperator`/`BinaryOperator`, by the updater's type and the number of operands. */
    private fun apply(function: Any?, vararg operands: Any?): Any? {
        val (method, param) = when (type) {
            'I' -> "applyAsInt" to "I"
            'J' -> "applyAsLong" to "J"
            else -> "apply" to "Ljava/lang/Object;"
        }
        return vm.callVirtual(function, method, "(" + param.repeat(operands.size) + ")" + param, operands.asList())
    }

    private fun one(sign: Int): Any = if (type == 'J') sign.toLong() else sign

    private fun plus(a: Any?, b: Any?): Any = if (type == 'J') (a as Long) + (b as Long) else (a as Int) + (b as Int)

    override fun toString(): String = "FieldUpdater[$description]"

    private companion object {
        /** A call descriptor that wants its result, for the accesses made on the program's behalf. */
        const val VALUE = "()Ljava/lang/Object;"
    }
}
