package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.ClassDefs
import dev.ide.vm.HostObject
import dev.ide.vm.VmClass
import dev.ide.vm.VmObject
import dev.ide.vm.VmRefArray
import dev.ide.vm.define
import dev.ide.vm.identityHash
import dev.ide.vm.javaDoubleToString
import dev.ide.vm.javaFloatToString
import dev.ide.vm.nanoTime
import dev.ide.platform.epochMillis
import dev.ide.vm.Call
import kotlin.math.IEEErem
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.withSign

/** `java.lang`: Object, Class, System, Math, Thread, Enum, the core interfaces, references. */
internal fun ClassDefs.registerLang() {
    define("java/lang/Object") {
        superName = null
        ctor("()V") {}
        method("hashCode", "()I") { ret(identityHash(r(0)!!)) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(0) === r(1)) }
        method("toString", "()Ljava/lang/String;") {
            val self = r(0)!!
            val hash = vm.vmHashCode(self)
            retRef(vm.classOf(self).javaName + "@" + hash.toUInt().toString(16))
        }
        method("getClass", "()Ljava/lang/Class;") { retRef(vm.mirror(vm.classOf(r(0)!!))) }
        method("clone", "()Ljava/lang/Object;") { retRef(vm.cloneValue(r(0)!!)) }
        method("notify", "()V") {}
        method("notifyAll", "()V") {}
        method("wait", "()V") {}
        method("wait", "(J)V") {}
        method("wait", "(JI)V") {}
        method("finalize", "()V") {}
    }

    for (name in listOf(
        "java/lang/Runnable", "java/lang/Cloneable", "java/io/Serializable", "java/lang/Comparable",
        "java/lang/AutoCloseable", "java/io/Closeable", "java/lang/Appendable", "java/util/RandomAccess",
        "java/lang/reflect/Type", "java/lang/reflect/AnnotatedElement", "java/lang/reflect/GenericDeclaration",
        "java/lang/annotation/Annotation", "java/util/EventListener", "java/io/Flushable",
        "java/lang/Thread\$UncaughtExceptionHandler", "java/lang/constant/Constable",
    )) define(name) { asInterface() }
    define("java/io/Closeable") { implements("java/lang/AutoCloseable") }

    define("java/lang/Iterable") {
        asInterface()
        method("forEach", "(Ljava/util/function/Consumer;)V") {
            val action = r(1)
            vm.forEachElement(r(0)) { vm.callVirtual(action, "accept", "(Ljava/lang/Object;)V", it) }
        }
    }

    define("java/lang/Class") {
        implements("java/io/Serializable", "java/lang/reflect/Type", "java/lang/reflect/AnnotatedElement", "java/lang/reflect/GenericDeclaration")
        method("getName", "()Ljava/lang/String;") { retRef(cls(0).javaName) }
        method("getTypeName", "()Ljava/lang/String;") { retRef(cls(0).typeName()) }
        method("getSimpleName", "()Ljava/lang/String;") { retRef(cls(0).simpleName()) }
        method("getCanonicalName", "()Ljava/lang/String;") { retRef(cls(0).typeName().replace('$', '.')) }
        method("toString", "()Ljava/lang/String;") {
            val c = cls(0)
            retRef(if (c.isPrimitive) c.name else (if (c.isInterface) "interface " else "class ") + c.javaName)
        }
        method("isInstance", "(Ljava/lang/Object;)Z") { val o = r(1); ret(o != null && vm.isInstance(o, cls(0))) }
        method("isAssignableFrom", "(Ljava/lang/Class;)Z") { ret(vm.isAssignable(cls(1), cls(0))) }
        method("isArray", "()Z") { ret(cls(0).isArray) }
        method("isInterface", "()Z") { ret(cls(0).isInterface) }
        method("isPrimitive", "()Z") { ret(cls(0).isPrimitive) }
        method("isEnum", "()Z") { ret(cls(0).superClass?.name == "java/lang/Enum") }
        method("isAnonymousClass", "()Z") { val p = cls(0).parsed; ret(p != null && p.isNested && p.innerName == null) }
        method("isLocalClass", "()Z") { val p = cls(0).parsed; ret(p != null && p.enclosingClass != null && p.innerName != null) }
        method("isMemberClass", "()Z") { val p = cls(0).parsed; ret(p != null && p.isNested && p.outerName != null && p.enclosingClass == null) }
        method("isSynthetic", "()Z") { ret(cls(0).access and 0x1000 != 0) }
        method("getEnclosingMethod", "()Ljava/lang/reflect/Method;") { retRef(null) }
        method("getEnclosingConstructor", "()Ljava/lang/reflect/Constructor;") { retRef(null) }
        method("getDeclaringClass", "()Ljava/lang/Class;") { retRef(cls(0).parsed?.outerName?.let { vm.mirror(vm.loadClass(it)) }) }
        method("isAnnotationPresent", "(Ljava/lang/Class;)Z") { ret(false) }
        method("getComponentType", "()Ljava/lang/Class;") { retRef(cls(0).componentClass?.let { vm.mirror(it) }) }
        method("componentType", "()Ljava/lang/Class;") { retRef(cls(0).componentClass?.let { vm.mirror(it) }) }
        method("getSuperclass", "()Ljava/lang/Class;") {
            val c = cls(0)
            retRef(if (c.isInterface) null else c.superClass?.let { vm.mirror(it) })
        }
        method("desiredAssertionStatus", "()Z") { ret(false) }
        method("getClassLoader", "()Ljava/lang/ClassLoader;") { retRef(null) }
        method("hashCode", "()I") { ret(identityHash(r(0)!!)) }
        method("getModifiers", "()I") { ret(cls(0).access) }
        method("cast", "(Ljava/lang/Object;)Ljava/lang/Object;") {
            val o = r(1)
            if (o != null && !vm.isInstance(o, cls(0))) vm.throwVm("java/lang/ClassCastException", "Cannot cast ${vm.classOf(o).javaName} to ${cls(0).javaName}")
            retRef(o)
        }
        method("getEnumConstants", "()[Ljava/lang/Object;") { retRef(vm.enumConstants(cls(0))) }
        method("getEnclosingClass", "()Ljava/lang/Class;") {
            val p = cls(0).parsed
            retRef((p?.enclosingClass ?: p?.outerName)?.let { vm.mirror(vm.loadClass(it)) })
        }
        method("getPackageName", "()Ljava/lang/String;") { retRef(cls(0).javaName.substringBeforeLast('.', "")) }
        method("getInterfaces", "()[Ljava/lang/Class;") {
            retRef(vm.newRefArray("java/lang/Class", cls(0).interfaces.toList().map { vm.mirror(it) }.toTypedArray()))
        }
    }

    define("java/lang/System") {
        staticField("out", "Ljava/io/PrintStream;")
        staticField("err", "Ljava/io/PrintStream;")
        onInit { cls ->
            cls.setStaticRef("out", "Ljava/io/PrintStream;", vm.printStream())
            cls.setStaticRef("err", "Ljava/io/PrintStream;", vm.printStream())
        }
        static("arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V") { arraycopy(vm, r(0), i(1), r(2), i(3), i(4)) }
        static("identityHashCode", "(Ljava/lang/Object;)I") { ret(r(0)?.let { identityHash(it) } ?: 0) }
        static("nanoTime", "()J") { ret(nanoTime()) }
        static("currentTimeMillis", "()J") { ret(epochMillis()) }
        static("getProperty", "(Ljava/lang/String;)Ljava/lang/String;") { retRef(vm.systemProperty(r(0) as String)) }
        static("getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") { retRef(vm.systemProperty(r(0) as String) ?: r(1)) }
        static("lineSeparator", "()Ljava/lang/String;") { retRef("\n") }
        static("getProperties", "()Ljava/util/Properties;") { retRef(vm.systemPropertiesObject()) }
        static("setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") {
            retRef(vm.callVirtual(vm.systemPropertiesObject(), "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", r(0), r(1)))
        }
        static("clearProperty", "(Ljava/lang/String;)Ljava/lang/String;") {
            retRef(vm.callVirtual(vm.systemPropertiesObject(), "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", r(0)))
        }
        static("getenv", "(Ljava/lang/String;)Ljava/lang/String;") { retRef(null) }
        static("gc", "()V") {}
        static("runFinalization", "()V") {}
        static("getSecurityManager", "()Ljava/lang/SecurityManager;") { retRef(null) }
    }

    define("java/io/PrintStream") {
        fun Call.emit(s: String) { (self().native as (String) -> Unit)(s) }
        method("println", "()V") { emit("\n") }
        method("println", "(Ljava/lang/String;)V") { emit(((r(1) as String?) ?: "null") + "\n") }
        method("println", "(Ljava/lang/Object;)V") { emit(vm.vmToString(r(1)) + "\n") }
        method("println", "(I)V") { emit(i(1).toString() + "\n") }
        method("println", "(J)V") { emit(l(1).toString() + "\n") }
        method("println", "(Z)V") { emit(z(1).toString() + "\n") }
        method("println", "(C)V") { emit(c(1).toString() + "\n") }
        method("println", "(F)V") { emit(javaFloatToString(f(1)) + "\n") }
        method("println", "(D)V") { emit(javaDoubleToString(d(1)) + "\n") }
        method("println", "([C)V") { emit((r(1) as CharArray).concatToString() + "\n") }
        method("print", "(Ljava/lang/String;)V") { emit((r(1) as String?) ?: "null") }
        method("print", "(Ljava/lang/Object;)V") { emit(vm.vmToString(r(1))) }
        method("print", "(I)V") { emit(i(1).toString()) }
        method("print", "(J)V") { emit(l(1).toString()) }
        method("print", "(Z)V") { emit(z(1).toString()) }
        method("print", "(C)V") { emit(c(1).toString()) }
        method("print", "(F)V") { emit(javaFloatToString(f(1))) }
        method("print", "(D)V") { emit(javaDoubleToString(d(1))) }
        method("flush", "()V") {}
    }

    define("java/lang/Math") { mathMethods() }
    define("java/lang/StrictMath") { mathMethods() }

    // One thread. A thread interpreted code starts is recorded and never run: the VM has no second thread to
    // run it on yet. Skia's cleaner thread is the common case, and not running it only means native objects
    // are freed when the VM is, not as they become unreachable.
    define("java/lang/Thread") {
        implements("java/lang/Runnable")
        fun Call.state(): ThreadState = (self().native as ThreadState?) ?: ThreadState("main", null).also { self().native = it }
        ctor("()V") { self().native = ThreadState("Thread-${vm.nextThreadNumber()}", null) }
        ctor("(Ljava/lang/Runnable;)V") { self().native = ThreadState("Thread-${vm.nextThreadNumber()}", r(1)) }
        ctor("(Ljava/lang/String;)V") { self().native = ThreadState(r(1) as String, null) }
        ctor("(Ljava/lang/Runnable;Ljava/lang/String;)V") { self().native = ThreadState(r(2) as String, r(1)) }
        ctor("(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;)V") { self().native = ThreadState("Thread-${vm.nextThreadNumber()}", r(2)) }
        ctor("(Ljava/lang/ThreadGroup;Ljava/lang/String;)V") { self().native = ThreadState(r(2) as String, null) }
        ctor("(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;)V") { self().native = ThreadState(r(3) as String, r(2)) }
        ctor("(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;J)V") { self().native = ThreadState(r(3) as String, r(2)) }
        static("currentThread", "()Ljava/lang/Thread;") { retRef(vm.mainThreadObject()) }
        method("run", "()V") { state().target?.let { vm.callVirtual(it, "run", "()V") } }
        method("start", "()V") {
            val s = state()
            if (s.started) vm.throwVm("java/lang/IllegalThreadStateException", null)
            s.started = true
        }
        method("getId", "()J") { ret(state().id) }
        method("threadId", "()J") { ret(state().id) }
        method("getName", "()Ljava/lang/String;") { retRef(state().name) }
        method("setName", "(Ljava/lang/String;)V") { state().name = r(1) as String }
        method("isDaemon", "()Z") { ret(state().daemon) }
        method("setDaemon", "(Z)V") { state().daemon = z(1) }
        method("getPriority", "()I") { ret(state().priority) }
        method("setPriority", "(I)V") { state().priority = i(1) }
        method("isAlive", "()Z") { ret(self() === vm.mainThreadObject()) }
        method("isInterrupted", "()Z") { ret(false) }
        method("interrupt", "()V") {}
        method("join", "()V") {}
        method("join", "(J)V") {}
        method("getContextClassLoader", "()Ljava/lang/ClassLoader;") { retRef(null) }
        method("setContextClassLoader", "(Ljava/lang/ClassLoader;)V") {}
        method("getStackTrace", "()[Ljava/lang/StackTraceElement;") { retRef(vm.newRefArray("java/lang/StackTraceElement", emptyArray())) }
        method("getUncaughtExceptionHandler", "()Ljava/lang/Thread\$UncaughtExceptionHandler;") { retRef(state().handler) }
        method("setUncaughtExceptionHandler", "(Ljava/lang/Thread\$UncaughtExceptionHandler;)V") { state().handler = r(1) }
        method("getThreadGroup", "()Ljava/lang/ThreadGroup;") { retRef(null) }
        method("toString", "()Ljava/lang/String;") { retRef("Thread[#${state().id},${state().name},5,main]") }
        static("interrupted", "()Z") { ret(false) }
        static("sleep", "(J)V") { vm.runPendingTasks() }
        static("sleep", "(JI)V") { vm.runPendingTasks() }
        static("yield", "()V") {}
        static("onSpinWait", "()V") {}
        static("holdsLock", "(Ljava/lang/Object;)Z") { ret(true) }
        static("getDefaultUncaughtExceptionHandler", "()Ljava/lang/Thread\$UncaughtExceptionHandler;") { retRef(null) }
        static("setDefaultUncaughtExceptionHandler", "(Ljava/lang/Thread\$UncaughtExceptionHandler;)V") {}
    }
    define("java/lang/ThreadGroup") {}
    define("java/lang/IllegalThreadStateException") {
        superName = "java/lang/IllegalArgumentException"
        ctor("()V") { self().native = ThrowableState(null) }
    }
    define("java/lang/StackTraceElement") {
        method("toString", "()Ljava/lang/String;") { retRef("?") }
    }

    define("java/lang/ThreadLocal") {
        ctor("()V") {}
        method("initialValue", "()Ljava/lang/Object;") { retRef(null) }
        method("get", "()Ljava/lang/Object;") {
            val self = self()
            val state = self.native as ThreadLocalState?
            if (state != null) { retRef(state.value); return@method }
            val initial = vm.callVirtual(self, "initialValue", "()Ljava/lang/Object;")
            self.native = ThreadLocalState(initial)
            retRef(initial)
        }
        method("set", "(Ljava/lang/Object;)V") { self().native = ThreadLocalState(r(1)) }
        method("remove", "()V") { self().native = null }
        static("withInitial", "(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;") {
            val tl = vm.newVmObject("java/lang/ThreadLocal\$SuppliedThreadLocal")
            tl.refs[0] = r(0)
            retRef(tl)
        }
    }
    define("java/lang/ThreadLocal\$SuppliedThreadLocal") {
        superName = "java/lang/ThreadLocal"
        field("supplier", "Ljava/util/function/Supplier;")
        method("initialValue", "()Ljava/lang/Object;") { retRef(vm.callVirtual(self().refs[0], "get", "()Ljava/lang/Object;")) }
    }
    define("java/lang/InheritableThreadLocal") { superName = "java/lang/ThreadLocal"; ctor("()V") {} }

    define("java/lang/Enum") {
        asAbstract()
        implements("java/lang/Comparable", "java/io/Serializable", "java/lang/constant/Constable")
        field("name", "Ljava/lang/String;") // ref slot 0
        field("ordinal", "I") // prim slot 0
        ctor("(Ljava/lang/String;I)V") { val s = self(); s.refs[0] = r(1); s.prims[0] = i(2).toLong() }
        method("name", "()Ljava/lang/String;") { retRef(self().refs[0]) }
        method("toString", "()Ljava/lang/String;") { retRef(self().refs[0]) }
        method("ordinal", "()I") { ret(self().prims[0].toInt()) }
        method("hashCode", "()I") { ret(identityHash(self())) }
        method("equals", "(Ljava/lang/Object;)Z") { ret(r(0) === r(1)) }
        method("compareTo", "(Ljava/lang/Enum;)I") { ret(self().prims[0].toInt() - (r(1) as VmObject).prims[0].toInt()) }
        method("compareTo", "(Ljava/lang/Object;)I") { ret(self().prims[0].toInt() - (r(1) as VmObject).prims[0].toInt()) }
        method("getDeclaringClass", "()Ljava/lang/Class;") {
            var c = self().cls
            if (c.superClass?.name != "java/lang/Enum") c = c.superClass!!
            retRef(vm.mirror(c))
        }
        static("valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;") {
            val c = ((r(0) as VmObject).native as VmClass)
            val name = r(1) as String
            val values = vm.enumConstants(c)?.data ?: emptyArray()
            retRef(values.firstOrNull { (it as VmObject).refs[0] == name }
                ?: vm.throwVm("java/lang/IllegalArgumentException", "No enum constant ${c.javaName}.$name"))
        }
    }

    define("java/lang/ref/Reference") {
        asAbstract()
        method("get", "()Ljava/lang/Object;") { retRef(self().native) }
        method("clear", "()V") { self().native = null }
        method("refersTo", "(Ljava/lang/Object;)Z") { ret(self().native === r(1)) }
        method("enqueue", "()Z") { ret(false) }
        static("reachabilityFence", "(Ljava/lang/Object;)V") {}
        method("isEnqueued", "()Z") { ret(false) }
    }
    for (kind in listOf("java/lang/ref/WeakReference", "java/lang/ref/SoftReference", "java/lang/ref/PhantomReference")) {
        define(kind) {
            superName = "java/lang/ref/Reference"
            ctor("(Ljava/lang/Object;)V") { self().native = r(1) }
            ctor("(Ljava/lang/Object;Ljava/lang/ref/ReferenceQueue;)V") { self().native = r(1) }
        }
    }
    define("java/lang/ref/ReferenceQueue") {
        ctor("()V") {}
        method("poll", "()Ljava/lang/ref/Reference;") { retRef(null) }
    }

    define("java/lang/Void") { staticField("TYPE", "Ljava/lang/Class;"); onInit { it.setStaticRef("TYPE", "Ljava/lang/Class;", vm.mirror(vm.primitiveClass('V'))) } }

    // Formatting and case mapping here are locale-independent (Kotlin's `uppercase()` passes Locale.ROOT
    // anyway), so a Locale is only its tag.
    define("java/util/Locale") {
        implements("java/io/Serializable", "java/lang/Cloneable")
        val constants = listOf("ROOT" to "", "US" to "en_US", "ENGLISH" to "en", "UK" to "en_GB")
        for ((name, _) in constants) staticField(name, "Ljava/util/Locale;")
        onInit { cls ->
            for ((name, tag) in constants) {
                val l = VmObject(cls); l.native = tag
                cls.setStaticRef(name, "Ljava/util/Locale;", l)
            }
        }
        ctor("(Ljava/lang/String;)V") { self().native = r(1) }
        ctor("(Ljava/lang/String;Ljava/lang/String;)V") { self().native = "${r(1)}_${r(2)}" }
        static("getDefault", "()Ljava/util/Locale;") {
            val cls = vm.loadClass("java/util/Locale"); vm.ensureInitialized(cls)
            retRef(cls.staticRefs[cls.fields["US:Ljava/util/Locale;"]!!.slot])
        }
        static("forLanguageTag", "(Ljava/lang/String;)Ljava/util/Locale;") {
            val l = vm.newVmObject("java/util/Locale"); l.native = (r(0) as String).replace('-', '_'); retRef(l)
        }
        method("toString", "()Ljava/lang/String;") { retRef(self().native as String) }
        method("toLanguageTag", "()Ljava/lang/String;") { retRef((self().native as String).replace('_', '-').ifEmpty { "und" }) }
        method("getLanguage", "()Ljava/lang/String;") { retRef((self().native as String).substringBefore('_')) }
        method("getCountry", "()Ljava/lang/String;") { retRef((self().native as String).substringAfter('_', "")) }
        method("equals", "(Ljava/lang/Object;)Z") { val o = r(1); ret(o is VmObject && o.cls === self().cls && o.native == self().native) }
        method("hashCode", "()I") { ret(javaStringHash(self().native as String)) }
    }

    define("java/lang/Runtime") {
        static("getRuntime", "()Ljava/lang/Runtime;") { retRef(vm.singleton("java/lang/Runtime")) }
        method("availableProcessors", "()I") { ret(1) }
        method("maxMemory", "()J") { ret(512L * 1024 * 1024) }
        method("totalMemory", "()J") { ret(256L * 1024 * 1024) }
        method("freeMemory", "()J") { ret(128L * 1024 * 1024) }
        method("gc", "()V") {}
    }
}

internal class ThreadLocalState(val value: Any?)

internal class ThreadState(var name: String, val target: Any?) {
    val id: Long = nextId++
    var daemon = false
    var priority = 5
    var started = false
    var handler: Any? = null

    private companion object { var nextId = 1L }
}

/** The VM's `System.getProperties()`: the host's [Vm.systemProperties] over the floor's defaults. */
internal fun Vm.systemPropertiesObject(): VmObject = singletons.getOrPut("\u0000properties") {
    val props = newVmObject("java/util/Properties")
    val map = JHashMap(this, linked = false)
    for (key in listOf("line.separator", "file.separator", "path.separator", "java.version", "java.specification.version", "os.name")) {
        defaultProperty(key)?.let { map.put(key, it) }
    }
    for ((k, v) in systemProperties) map.put(k, v)
    props.native = map
    props
}

internal fun Vm.systemProperty(name: String): String? =
    (systemPropertiesObject().native as JHashMap).get(name) as? String

private fun defaultProperty(name: String): String? = when (name) {
    "line.separator" -> "\n"
    "file.separator" -> "/"
    "path.separator" -> ":"
    "java.version" -> "17"
    "java.specification.version" -> "17"
    "os.name" -> "CodeAssist VM"
    else -> null
}

private fun dev.ide.vm.NativeClassBuilder.mathMethods() {
    static("abs", "(I)I") { ret(abs(i(0))) }
    static("abs", "(J)J") { ret(abs(l(0))) }
    static("abs", "(F)F") { ret(abs(f(0))) }
    static("abs", "(D)D") { ret(abs(d(0))) }
    static("max", "(II)I") { ret(maxOf(i(0), i(1))) }
    static("max", "(JJ)J") { ret(maxOf(l(0), l(2))) }
    static("max", "(FF)F") { ret(javaMax(f(0), f(1))) }
    static("max", "(DD)D") { ret(javaMax(d(0), d(2))) }
    static("min", "(II)I") { ret(minOf(i(0), i(1))) }
    static("min", "(JJ)J") { ret(minOf(l(0), l(2))) }
    static("min", "(FF)F") { ret(javaMin(f(0), f(1))) }
    static("min", "(DD)D") { ret(javaMin(d(0), d(2))) }
    static("sqrt", "(D)D") { ret(sqrt(d(0))) }
    static("cbrt", "(D)D") { ret(cbrt(d(0))) }
    static("pow", "(DD)D") { ret(d(0).pow(d(2))) }
    static("exp", "(D)D") { ret(exp(d(0))) }
    static("expm1", "(D)D") { ret(expm1(d(0))) }
    static("log", "(D)D") { ret(ln(d(0))) }
    static("log10", "(D)D") { ret(log10(d(0))) }
    static("log1p", "(D)D") { ret(ln1p(d(0))) }
    static("sin", "(D)D") { ret(sin(d(0))) }
    static("cos", "(D)D") { ret(cos(d(0))) }
    static("tan", "(D)D") { ret(tan(d(0))) }
    static("asin", "(D)D") { ret(asin(d(0))) }
    static("acos", "(D)D") { ret(acos(d(0))) }
    static("atan", "(D)D") { ret(atan(d(0))) }
    static("atan2", "(DD)D") { ret(atan2(d(0), d(2))) }
    static("sinh", "(D)D") { ret(sinh(d(0))) }
    static("cosh", "(D)D") { ret(cosh(d(0))) }
    static("tanh", "(D)D") { ret(tanh(d(0))) }
    static("hypot", "(DD)D") { ret(hypot(d(0), d(2))) }
    static("floor", "(D)D") { ret(floor(d(0))) }
    static("ceil", "(D)D") { ret(ceil(d(0))) }
    static("rint", "(D)D") { ret(round(d(0))) }
    static("IEEEremainder", "(DD)D") { ret(d(0).IEEErem(d(2))) }
    static("round", "(F)I") { val v = f(0); ret(if (v.isNaN()) 0 else floor(v + 0.5f).let { if (it >= Int.MAX_VALUE.toFloat()) Int.MAX_VALUE else if (it <= Int.MIN_VALUE.toFloat()) Int.MIN_VALUE else it.toInt() }) }
    static("round", "(D)J") { val v = d(0); ret(if (v.isNaN()) 0L else floor(v + 0.5).toLong()) }
    static("signum", "(F)F") { ret(sign(f(0))) }
    static("signum", "(D)D") { ret(sign(d(0))) }
    static("copySign", "(FF)F") { ret(f(0).withSign(f(1))) }
    static("copySign", "(DD)D") { ret(d(0).withSign(d(2))) }
    static("toRadians", "(D)D") { ret(d(0) / 180.0 * kotlin.math.PI) }
    static("toDegrees", "(D)D") { ret(d(0) * 180.0 / kotlin.math.PI) }
    static("floorDiv", "(II)I") { ret(i(0).floorDiv(i(1).also { if (it == 0) vm.throwVm("java/lang/ArithmeticException", "/ by zero") })) }
    static("floorDiv", "(JJ)J") { ret(l(0).floorDiv(l(2).also { if (it == 0L) vm.throwVm("java/lang/ArithmeticException", "/ by zero") })) }
    static("floorDiv", "(JI)J") { ret(l(0).floorDiv(i(2).toLong().also { if (it == 0L) vm.throwVm("java/lang/ArithmeticException", "/ by zero") })) }
    static("floorMod", "(II)I") { ret(i(0).mod(i(1).also { if (it == 0) vm.throwVm("java/lang/ArithmeticException", "/ by zero") })) }
    static("floorMod", "(JJ)J") { ret(l(0).mod(l(2).also { if (it == 0L) vm.throwVm("java/lang/ArithmeticException", "/ by zero") })) }
    static("floorMod", "(JI)I") { ret(l(0).mod(i(2).toLong().also { if (it == 0L) vm.throwVm("java/lang/ArithmeticException", "/ by zero") }).toInt()) }
    static("addExact", "(II)I") { val r = i(0).toLong() + i(1); if (r.toInt().toLong() != r) vm.throwVm("java/lang/ArithmeticException", "integer overflow"); ret(r.toInt()) }
    static("subtractExact", "(II)I") { val r = i(0).toLong() - i(1); if (r.toInt().toLong() != r) vm.throwVm("java/lang/ArithmeticException", "integer overflow"); ret(r.toInt()) }
    static("multiplyExact", "(II)I") { val r = i(0).toLong() * i(1); if (r.toInt().toLong() != r) vm.throwVm("java/lang/ArithmeticException", "integer overflow"); ret(r.toInt()) }
    static("toIntExact", "(J)I") { val v = l(0); if (v.toInt().toLong() != v) vm.throwVm("java/lang/ArithmeticException", "integer overflow"); ret(v.toInt()) }
    static("addExact", "(JJ)J") {
        val a = l(0); val b = l(2); val r = a + b
        if (((a xor r) and (b xor r)) < 0) vm.throwVm("java/lang/ArithmeticException", "long overflow"); ret(r)
    }
    static("subtractExact", "(JJ)J") {
        val a = l(0); val b = l(2); val r = a - b
        if (((a xor b) and (a xor r)) < 0) vm.throwVm("java/lang/ArithmeticException", "long overflow"); ret(r)
    }
    static("multiplyExact", "(JJ)J") {
        val a = l(0); val b = l(2); val r = a * b
        if (a != 0L && (r / a != b || (a == -1L && b == Long.MIN_VALUE))) vm.throwVm("java/lang/ArithmeticException", "long overflow"); ret(r)
    }
    static("negateExact", "(I)I") { if (i(0) == Int.MIN_VALUE) vm.throwVm("java/lang/ArithmeticException", "integer overflow"); ret(-i(0)) }
    static("random", "()D") { ret(kotlin.random.Random.nextDouble()) }
    static("ulp", "(D)D") { ret(d(0).ulpValue()) }
    static("ulp", "(F)F") { ret(f(0).ulpValue()) }
    static("nextUp", "(D)D") { ret(d(0).nextUpValue()) }
    static("fma", "(FFF)F") { ret(f(0) * f(1) + f(2)) }
    static("fma", "(DDD)D") { ret(d(0) * d(2) + d(4)) }
}

private fun Double.ulpValue(): Double = kotlin.math.abs(this).let { it.nextUpValue() - it }
private fun Float.ulpValue(): Float = kotlin.math.abs(this).let { Float.fromBits(it.toBits() + 1) - it }
private fun Double.nextUpValue(): Double = if (isNaN() || this == Double.POSITIVE_INFINITY) this else
    if (this == 0.0) Double.MIN_VALUE else Double.fromBits(toRawBits() + if (this > 0) 1 else -1)

private fun javaMax(a: Float, b: Float): Float = when {
    a.isNaN() || b.isNaN() -> Float.NaN
    a == 0f && b == 0f -> if (a.toRawBits() == 0) a else b
    else -> if (a >= b) a else b
}
private fun javaMin(a: Float, b: Float): Float = when {
    a.isNaN() || b.isNaN() -> Float.NaN
    a == 0f && b == 0f -> if (a.toRawBits() != 0) a else b
    else -> if (a <= b) a else b
}
private fun javaMax(a: Double, b: Double): Double = when {
    a.isNaN() || b.isNaN() -> Double.NaN
    a == 0.0 && b == 0.0 -> if (a.toRawBits() == 0L) a else b
    else -> if (a >= b) a else b
}
private fun javaMin(a: Double, b: Double): Double = when {
    a.isNaN() || b.isNaN() -> Double.NaN
    a == 0.0 && b == 0.0 -> if (a.toRawBits() != 0L) a else b
    else -> if (a <= b) a else b
}

internal fun arraycopy(vm: dev.ide.vm.Vm, src: Any?, srcPos: Int, dst: Any?, dstPos: Int, length: Int) {
    if (src == null || dst == null) vm.throwVm("java/lang/NullPointerException", "arraycopy")
    if (length < 0 || srcPos < 0 || dstPos < 0) vm.throwVm("java/lang/ArrayIndexOutOfBoundsException", "arraycopy: length $length")
    when (src) {
        is VmRefArray -> {
            if (dst !is VmRefArray) vm.throwVm("java/lang/ArrayStoreException", "arraycopy")
            checkRange(vm, src.data.size, srcPos, dst.data.size, dstPos, length)
            src.data.copyInto(dst.data, dstPos, srcPos, srcPos + length)
        }
        is IntArray -> { val d = dst as IntArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is LongArray -> { val d = dst as LongArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is CharArray -> { val d = dst as CharArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is ByteArray -> { val d = dst as ByteArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is FloatArray -> { val d = dst as FloatArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is DoubleArray -> { val d = dst as DoubleArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is BooleanArray -> { val d = dst as BooleanArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        is ShortArray -> { val d = dst as ShortArray; checkRange(vm, src.size, srcPos, d.size, dstPos, length); src.copyInto(d, dstPos, srcPos, srcPos + length) }
        else -> vm.throwVm("java/lang/ArrayStoreException", "arraycopy: source type ${vm.classOf(src).javaName} is not an array")
    }
}

private fun checkRange(vm: dev.ide.vm.Vm, srcSize: Int, srcPos: Int, dstSize: Int, dstPos: Int, length: Int) {
    if (srcPos + length > srcSize || dstPos + length > dstSize) {
        vm.throwVm("java/lang/ArrayIndexOutOfBoundsException", "arraycopy: last index out of bounds")
    }
}

/** The receiver (or argument [n]) of a `java.lang.Class` method, as the class it mirrors. */
internal fun Call.cls(n: Int): VmClass = (r(n) as VmObject).native as VmClass


