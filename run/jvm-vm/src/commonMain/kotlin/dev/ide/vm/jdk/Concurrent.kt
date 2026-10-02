package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.HostObject
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.VmClass
import dev.ide.vm.VmField
import dev.ide.vm.VmObject
import dev.ide.vm.define

/** A field updater: the field it names, found once, then read and written by slot. */
internal class FieldUpdater(override val vmClass: VmClass, val field: VmField) : HostObject

/**
 * `java.util.concurrent` and `java.util.function`. The VM is single-threaded, so every atomic is a plain
 * read-modify-write: there is no other thread to race with, and the result is what a sequential run of the
 * same program produces.
 */
internal fun ClassDefs.registerConcurrent() {
    val functional = listOf(
        "Function", "BiFunction", "Supplier", "Consumer", "BiConsumer", "Predicate", "BiPredicate",
        "UnaryOperator", "BinaryOperator", "IntFunction", "IntUnaryOperator", "IntBinaryOperator", "IntPredicate",
        "IntSupplier", "IntConsumer", "ToIntFunction", "ToLongFunction", "ToDoubleFunction", "ToIntBiFunction",
        "LongFunction", "LongUnaryOperator", "LongBinaryOperator", "LongSupplier", "LongPredicate", "LongConsumer",
        "DoubleFunction", "DoubleUnaryOperator", "DoubleBinaryOperator", "DoubleSupplier", "DoublePredicate",
        "BooleanSupplier", "ObjIntConsumer", "IntToLongFunction", "IntToDoubleFunction",
    )
    for (f in functional) define("java/util/function/$f") { asInterface() }
    define("java/util/function/UnaryOperator") { implements("java/util/function/Function") }
    define("java/util/function/BinaryOperator") { implements("java/util/function/BiFunction") }
    define("java/util/function/Function") {
        static("identity", "()Ljava/util/function/Function;") { retRef(vm.identityFunction()) }
    }
    define("java/util/function/UnaryOperator") {
        static("identity", "()Ljava/util/function/UnaryOperator;") { retRef(vm.identityFunction()) }
    }
    define("java/util/HostIdentity") {
        implements("java/util/function/UnaryOperator")
        method("apply", "(Ljava/lang/Object;)Ljava/lang/Object;") { retRef(r(1)) }
    }
    define("java/util/concurrent/Callable") { asInterface() }

    define("java/util/concurrent/TimeUnit") {
        superName = "java/lang/Enum"
        val units = listOf(
            "NANOSECONDS" to 1L, "MICROSECONDS" to 1_000L, "MILLISECONDS" to 1_000_000L, "SECONDS" to 1_000_000_000L,
            "MINUTES" to 60_000_000_000L, "HOURS" to 3_600_000_000_000L, "DAYS" to 86_400_000_000_000L,
        )
        for ((name, _) in units) staticField(name, "Ljava/util/concurrent/TimeUnit;")
        staticField("\$VALUES", "[Ljava/util/concurrent/TimeUnit;")
        onInit { cls ->
            val all = units.mapIndexed { ordinal, (name, nanos) ->
                VmObject(cls).also { it.refs[0] = name; it.prims[0] = ordinal.toLong(); it.native = nanos; cls.setStaticRef(name, "Ljava/util/concurrent/TimeUnit;", it) }
            }
            cls.setStaticRef("\$VALUES", "[Ljava/util/concurrent/TimeUnit;", vm.newRefArray("java/util/concurrent/TimeUnit", all.toTypedArray()))
        }
        fun Call.scale(n: Int = 0): Long = (r(n) as VmObject).native as Long
        fun convert(d: Long, from: Long, to: Long): Long = when {
            from == to -> d
            from > to -> { val ratio = from / to; if (d > Long.MAX_VALUE / ratio) Long.MAX_VALUE else if (d < Long.MIN_VALUE / ratio) Long.MIN_VALUE else d * ratio }
            else -> d / (to / from)
        }
        for ((method, target) in listOf("toNanos" to 1L, "toMicros" to 1_000L, "toMillis" to 1_000_000L, "toSeconds" to 1_000_000_000L,
            "toMinutes" to 60_000_000_000L, "toHours" to 3_600_000_000_000L, "toDays" to 86_400_000_000_000L)) {
            method(method, "(J)J") { ret(convert(l(1), scale(), target)) }
        }
        method("convert", "(JLjava/util/concurrent/TimeUnit;)J") { ret(convert(l(1), scale(3), scale())) }
        method("sleep", "(J)V") {}
        static("values", "()[Ljava/util/concurrent/TimeUnit;") {
            val cls = vm.loadClass("java/util/concurrent/TimeUnit"); vm.ensureInitialized(cls)
            val all = cls.staticRefs[cls.fields["\$VALUES:[Ljava/util/concurrent/TimeUnit;"]!!.slot] as VmRefArray
            retRef(VmRefArray(all.cls, all.data.copyOf()))
        }
    }
    define("java/util/concurrent/Executor") { asInterface() }

    define("java/util/concurrent/atomic/AtomicInteger") { atomicNumber(wide = false) }
    define("java/util/concurrent/atomic/AtomicLong") { atomicNumber(wide = true) }
    define("java/util/concurrent/atomic/AtomicBoolean") {
        implements("java/io/Serializable")
        field("value", "I")
        ctor("()V") {}
        ctor("(Z)V") { self().prims[0] = if (z(1)) 1 else 0 }
        method("get", "()Z") { ret(self().prims[0] != 0L) }
        method("getPlain", "()Z") { ret(self().prims[0] != 0L) }
        method("getAcquire", "()Z") { ret(self().prims[0] != 0L) }
        method("set", "(Z)V") { self().prims[0] = if (z(1)) 1 else 0 }
        method("lazySet", "(Z)V") { self().prims[0] = if (z(1)) 1 else 0 }
        method("setRelease", "(Z)V") { self().prims[0] = if (z(1)) 1 else 0 }
        method("getAndSet", "(Z)Z") { val s = self(); val old = s.prims[0] != 0L; s.prims[0] = if (z(1)) 1 else 0; ret(old) }
        for (cas in listOf("compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile")) {
            method(cas, "(ZZ)Z") {
                val s = self()
                if ((s.prims[0] != 0L) == z(1)) { s.prims[0] = if (z(2)) 1 else 0; ret(true) } else ret(false)
            }
        }
        method("toString", "()Ljava/lang/String;") { retRef((self().prims[0] != 0L).toString()) }
    }
    define("java/util/concurrent/atomic/AtomicReference") {
        implements("java/io/Serializable")
        field("value", "Ljava/lang/Object;")
        ctor("()V") {}
        ctor("(Ljava/lang/Object;)V") { self().refs[0] = r(1) }
        for (get in listOf("get", "getPlain", "getAcquire", "getOpaque")) method(get, "()Ljava/lang/Object;") { retRef(self().refs[0]) }
        for (set in listOf("set", "lazySet", "setPlain", "setRelease", "setOpaque")) method(set, "(Ljava/lang/Object;)V") { self().refs[0] = r(1) }
        method("getAndSet", "(Ljava/lang/Object;)Ljava/lang/Object;") { val s = self(); val old = s.refs[0]; s.refs[0] = r(1); retRef(old) }
        for (cas in listOf("compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile")) {
            method(cas, "(Ljava/lang/Object;Ljava/lang/Object;)Z") {
                val s = self()
                if (s.refs[0] === r(1)) { s.refs[0] = r(2); ret(true) } else ret(false)
            }
        }
        method("getAndUpdate", "(Ljava/util/function/UnaryOperator;)Ljava/lang/Object;") {
            val s = self(); val old = s.refs[0]
            s.refs[0] = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", old); retRef(old)
        }
        method("updateAndGet", "(Ljava/util/function/UnaryOperator;)Ljava/lang/Object;") {
            val s = self()
            s.refs[0] = vm.callVirtual(r(1), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", s.refs[0]); retRef(s.refs[0])
        }
        method("getAndAccumulate", "(Ljava/lang/Object;Ljava/util/function/BinaryOperator;)Ljava/lang/Object;") {
            val s = self(); val old = s.refs[0]
            s.refs[0] = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", old, r(1)); retRef(old)
        }
        method("accumulateAndGet", "(Ljava/lang/Object;Ljava/util/function/BinaryOperator;)Ljava/lang/Object;") {
            val s = self()
            s.refs[0] = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", s.refs[0], r(1)); retRef(s.refs[0])
        }
        method("toString", "()Ljava/lang/String;") { retRef(vm.vmToString(self().refs[0])) }
    }
    define("java/util/concurrent/atomic/AtomicReferenceArray") {
        ctor("(I)V") { self().native = arrayOfNulls<Any?>(i(1)) }
        method("length", "()I") { ret((self().native as Array<*>).size) }
        method("get", "(I)Ljava/lang/Object;") { retRef((self().native as Array<*>)[i(1)]) }
        method("set", "(ILjava/lang/Object;)V") { @Suppress("UNCHECKED_CAST") (self().native as Array<Any?>)[i(1)] = r(2) }
        method("lazySet", "(ILjava/lang/Object;)V") { @Suppress("UNCHECKED_CAST") (self().native as Array<Any?>)[i(1)] = r(2) }
        method("getAndSet", "(ILjava/lang/Object;)Ljava/lang/Object;") {
            @Suppress("UNCHECKED_CAST") val a = self().native as Array<Any?>
            val old = a[i(1)]; a[i(1)] = r(2); retRef(old)
        }
        method("compareAndSet", "(ILjava/lang/Object;Ljava/lang/Object;)Z") {
            @Suppress("UNCHECKED_CAST") val a = self().native as Array<Any?>
            if (a[i(1)] === r(2)) { a[i(1)] = r(3); ret(true) } else ret(false)
        }
    }
    define("java/util/concurrent/atomic/AtomicIntegerArray") {
        ctor("(I)V") { self().native = IntArray(i(1)) }
        method("length", "()I") { ret((self().native as IntArray).size) }
        method("get", "(I)I") { ret((self().native as IntArray)[i(1)]) }
        method("set", "(II)V") { (self().native as IntArray)[i(1)] = i(2) }
        method("getAndIncrement", "(I)I") { val a = self().native as IntArray; ret(a[i(1)]++) }
        method("incrementAndGet", "(I)I") { val a = self().native as IntArray; ret(++a[i(1)]) }
        method("compareAndSet", "(III)Z") { val a = self().native as IntArray; if (a[i(1)] == i(2)) { a[i(1)] = i(3); ret(true) } else ret(false) }
    }

    define("java/util/concurrent/atomic/AtomicReferenceFieldUpdater") {
        asAbstract()
        static("newUpdater", "(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;") {
            retRef(FieldUpdater(vm.loadClass("java/util/concurrent/atomic/AtomicReferenceFieldUpdater\$Impl"), updaterField(this, 2)))
        }
    }
    define("java/util/concurrent/atomic/AtomicReferenceFieldUpdater\$Impl") {
        superName = "java/util/concurrent/atomic/AtomicReferenceFieldUpdater"
        fun Call.obj(): VmObject = r(1) as VmObject? ?: vm.throwVm("java/lang/NullPointerException", "field updater target")
        fun Call.slot(): Int = (r(0) as FieldUpdater).field.slot
        for (cas in listOf("compareAndSet", "weakCompareAndSet")) {
            method(cas, "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z") {
                val o = obj(); val k = slot()
                if (o.refs[k] === r(2)) { o.refs[k] = r(3); ret(true) } else ret(false)
            }
        }
        method("get", "(Ljava/lang/Object;)Ljava/lang/Object;") { retRef(obj().refs[slot()]) }
        method("set", "(Ljava/lang/Object;Ljava/lang/Object;)V") { obj().refs[slot()] = r(2) }
        method("lazySet", "(Ljava/lang/Object;Ljava/lang/Object;)V") { obj().refs[slot()] = r(2) }
        method("getAndSet", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;") {
            val o = obj(); val k = slot(); val old = o.refs[k]; o.refs[k] = r(2); retRef(old)
        }
        method("getAndUpdate", "(Ljava/lang/Object;Ljava/util/function/UnaryOperator;)Ljava/lang/Object;") {
            val o = obj(); val k = slot(); val old = o.refs[k]
            o.refs[k] = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", old); retRef(old)
        }
        method("updateAndGet", "(Ljava/lang/Object;Ljava/util/function/UnaryOperator;)Ljava/lang/Object;") {
            val o = obj(); val k = slot()
            o.refs[k] = vm.callVirtual(r(2), "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", o.refs[k]); retRef(o.refs[k])
        }
    }
    for ((name, wide) in listOf("Integer" to false, "Long" to true)) {
        val t = if (wide) "J" else "I"
        define("java/util/concurrent/atomic/Atomic${name}FieldUpdater") {
            asAbstract()
            static("newUpdater", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/util/concurrent/atomic/Atomic${name}FieldUpdater;") {
                retRef(FieldUpdater(vm.loadClass("java/util/concurrent/atomic/Atomic${name}FieldUpdater\$Impl"), updaterField(this, 1)))
            }
        }
        define("java/util/concurrent/atomic/Atomic${name}FieldUpdater\$Impl") {
            superName = "java/util/concurrent/atomic/Atomic${name}FieldUpdater"
            fun Call.obj(): VmObject = r(1) as VmObject? ?: vm.throwVm("java/lang/NullPointerException", "field updater target")
            fun Call.slot(): Int = (r(0) as FieldUpdater).field.slot
            fun Call.arg(n: Int): Long = if (wide) l(n) else i(n).toLong()
            fun Call.result(v: Long) = if (wide) ret(v) else ret(v.toInt())
            fun norm(v: Long) = if (wide) v else v.toInt().toLong()
            val w = if (wide) 2 else 1
            for (cas in listOf("compareAndSet", "weakCompareAndSet")) {
                method(cas, "(Ljava/lang/Object;$t$t)Z") {
                    val o = obj(); val k = slot()
                    if (o.prims[k] == arg(2)) { o.prims[k] = arg(2 + w); ret(true) } else ret(false)
                }
            }
            method("get", "(Ljava/lang/Object;)$t") { result(obj().prims[slot()]) }
            method("set", "(Ljava/lang/Object;$t)V") { obj().prims[slot()] = arg(2) }
            method("lazySet", "(Ljava/lang/Object;$t)V") { obj().prims[slot()] = arg(2) }
            method("getAndSet", "(Ljava/lang/Object;$t)$t") { val o = obj(); val k = slot(); val old = o.prims[k]; o.prims[k] = arg(2); result(old) }
            method("getAndIncrement", "(Ljava/lang/Object;)$t") { val o = obj(); val k = slot(); val old = o.prims[k]; o.prims[k] = norm(old + 1); result(old) }
            method("getAndDecrement", "(Ljava/lang/Object;)$t") { val o = obj(); val k = slot(); val old = o.prims[k]; o.prims[k] = norm(old - 1); result(old) }
            method("incrementAndGet", "(Ljava/lang/Object;)$t") { val o = obj(); val k = slot(); o.prims[k] = norm(o.prims[k] + 1); result(o.prims[k]) }
            method("decrementAndGet", "(Ljava/lang/Object;)$t") { val o = obj(); val k = slot(); o.prims[k] = norm(o.prims[k] - 1); result(o.prims[k]) }
            method("getAndAdd", "(Ljava/lang/Object;$t)$t") { val o = obj(); val k = slot(); val old = o.prims[k]; o.prims[k] = norm(old + arg(2)); result(old) }
            method("addAndGet", "(Ljava/lang/Object;$t)$t") { val o = obj(); val k = slot(); o.prims[k] = norm(o.prims[k] + arg(2)); result(o.prims[k]) }
        }
    }

    define("java/util/concurrent/locks/Lock") { asInterface() }
    define("java/util/concurrent/locks/Condition") { asInterface() }
    define("java/util/concurrent/locks/ReadWriteLock") { asInterface() }
    define("java/util/concurrent/locks/ReentrantLock") {
        implements("java/util/concurrent/locks/Lock", "java/io/Serializable")
        field("holds", "I")
        ctor("()V") {}
        ctor("(Z)V") {}
        method("lock", "()V") { self().prims[0]++ }
        method("lockInterruptibly", "()V") { self().prims[0]++ }
        method("tryLock", "()Z") { self().prims[0]++; ret(true) }
        method("tryLock", "(JLjava/util/concurrent/TimeUnit;)Z") { self().prims[0]++; ret(true) }
        method("unlock", "()V") {
            val s = self()
            if (s.prims[0] <= 0L) vm.throwVm("java/lang/IllegalMonitorStateException", null)
            s.prims[0]--
        }
        method("isLocked", "()Z") { ret(self().prims[0] > 0L) }
        method("isHeldByCurrentThread", "()Z") { ret(self().prims[0] > 0L) }
        method("getHoldCount", "()I") { ret(self().prims[0].toInt()) }
        method("newCondition", "()Ljava/util/concurrent/locks/Condition;") { retRef(vm.newVmObject("java/util/concurrent/locks/HostCondition")) }
    }
    define("java/util/concurrent/locks/HostCondition") {
        implements("java/util/concurrent/locks/Condition")
        method("signal", "()V") {}
        method("signalAll", "()V") {}
        method("await", "()V") { vm.throwVm("java/lang/IllegalMonitorStateException", "await would block forever on a single-threaded VM") }
    }
    define("java/util/concurrent/locks/ReentrantReadWriteLock") {
        implements("java/util/concurrent/locks/ReadWriteLock", "java/io/Serializable")
        field("read", "Ljava/util/concurrent/locks/ReentrantLock;")
        field("write", "Ljava/util/concurrent/locks/ReentrantLock;")
        ctor("()V") { initReadWrite(this) }
        ctor("(Z)V") { initReadWrite(this) }
        method("readLock", "()Ljava/util/concurrent/locks/Lock;") { retRef(self().refs[0]) }
        method("writeLock", "()Ljava/util/concurrent/locks/Lock;") { retRef(self().refs[1]) }
    }
    // Parking is where a thread waits for another to do something; the one thread runs what is queued instead
    // (see Threading), and returns as a spurious wake-up, which every caller of park must tolerate.
    define("java/util/concurrent/locks/LockSupport") {
        static("park", "()V") { vm.runPendingTasks() }
        static("park", "(Ljava/lang/Object;)V") { vm.runPendingTasks() }
        static("parkNanos", "(J)V") { vm.runPendingTasks() }
        static("parkNanos", "(Ljava/lang/Object;J)V") { vm.runPendingTasks() }
        static("unpark", "(Ljava/lang/Thread;)V") {}
    }
}

private fun initReadWrite(call: Call) {
    val s = call.self()
    s.refs[0] = call.vm.newVmObject("java/util/concurrent/locks/ReentrantLock")
    s.refs[1] = call.vm.newVmObject("java/util/concurrent/locks/ReentrantLock")
}

/** The field a `newUpdater(Class, ..., String)` names; the name is argument [nameWord]. */
private fun updaterField(call: Call, nameWord: Int): VmField {
    val cls = call.cls(0)
    val name = call.r(nameWord) as String
    var c: VmClass? = cls
    while (c != null) {
        c.declaredFieldNamed(name)?.let { return it }
        c = c.superClass
    }
    call.vm.throwVm("java/lang/RuntimeException", "java.lang.NoSuchFieldException: $name")
}

private fun NativeClassBuilder.atomicNumber(wide: Boolean) {
    superName = "java/lang/Number"
    implements("java/io/Serializable")
    val t = if (wide) "J" else "I"
    field("value", t)
    fun Call.arg(n: Int): Long = if (wide) l(n) else i(n).toLong()
    fun Call.result(v: Long) = if (wide) ret(v) else ret(v.toInt())
    fun norm(v: Long) = if (wide) v else v.toInt().toLong()
    ctor("()V") {}
    ctor("($t)V") { self().prims[0] = arg(1) }
    for (get in listOf("get", "getPlain", "getAcquire", "getOpaque")) method(get, "()$t") { result(self().prims[0]) }
    for (set in listOf("set", "lazySet", "setPlain", "setRelease", "setOpaque")) method(set, "($t)V") { self().prims[0] = arg(1) }
    method("getAndSet", "($t)$t") { val s = self(); val old = s.prims[0]; s.prims[0] = arg(1); result(old) }
    for (cas in listOf("compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain", "weakCompareAndSetVolatile")) {
        method(cas, "($t$t)Z") {
            val s = self()
            if (s.prims[0] == arg(1)) { s.prims[0] = arg(if (wide) 3 else 2); ret(true) } else ret(false)
        }
    }
    method("getAndIncrement", "()$t") { val s = self(); val old = s.prims[0]; s.prims[0] = norm(old + 1); result(old) }
    method("getAndDecrement", "()$t") { val s = self(); val old = s.prims[0]; s.prims[0] = norm(old - 1); result(old) }
    method("incrementAndGet", "()$t") { val s = self(); s.prims[0] = norm(s.prims[0] + 1); result(s.prims[0]) }
    method("decrementAndGet", "()$t") { val s = self(); s.prims[0] = norm(s.prims[0] - 1); result(s.prims[0]) }
    method("getAndAdd", "($t)$t") { val s = self(); val old = s.prims[0]; s.prims[0] = norm(old + arg(1)); result(old) }
    method("addAndGet", "($t)$t") { val s = self(); s.prims[0] = norm(s.prims[0] + arg(1)); result(s.prims[0]) }
    val op = if (wide) "Long" else "Int"
    method("getAndUpdate", "(Ljava/util/function/${op}UnaryOperator;)$t") {
        val s = self(); val old = s.prims[0]
        s.prims[0] = norm((vm.callVirtual(r(1), "applyAs$op", "($t)$t", if (wide) old else old.toInt()) as Number).toLong()); result(old)
    }
    method("updateAndGet", "(Ljava/util/function/${op}UnaryOperator;)$t") {
        val s = self()
        s.prims[0] = norm((vm.callVirtual(r(1), "applyAs$op", "($t)$t", if (wide) s.prims[0] else s.prims[0].toInt()) as Number).toLong()); result(s.prims[0])
    }
    method("intValue", "()I") { ret(self().prims[0].toInt()) }
    method("longValue", "()J") { ret(self().prims[0]) }
    method("floatValue", "()F") { ret(self().prims[0].toFloat()) }
    method("doubleValue", "()D") { ret(self().prims[0].toDouble()) }
    method("toString", "()Ljava/lang/String;") { retRef(self().prims[0].toString()) }
}
