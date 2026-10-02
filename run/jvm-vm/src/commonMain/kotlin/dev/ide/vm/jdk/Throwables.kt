package dev.ide.vm.jdk

import dev.ide.vm.*
import dev.ide.vm.Call
import dev.ide.vm.ClassDefs
import dev.ide.vm.NativeClassBuilder
import dev.ide.vm.ThrowableState
import dev.ide.vm.VmObject
import dev.ide.vm.define

private fun Call.state(): ThrowableState {
    val self = self()
    return self.native as ThrowableState? ?: ThrowableState(null).also { self.native = it }
}

/** `Throwable` and the exception classes the JDK defines and that libraries throw or catch. */
internal fun ClassDefs.registerThrowables() {
    define("java/lang/Throwable") {
        implements("java/io/Serializable")
        ctor("()V") { self().native = ThrowableState(null) }
        ctor("(Ljava/lang/String;)V") { self().native = ThrowableState(r(1) as String?) }
        ctor("(Ljava/lang/String;Ljava/lang/Throwable;)V") {
            self().native = ThrowableState(r(1) as String?).also { it.cause = r(2); it.causeSet = true }
        }
        ctor("(Ljava/lang/String;Ljava/lang/Throwable;ZZ)V") {
            self().native = ThrowableState(r(1) as String?).also { it.cause = r(2); it.causeSet = true }
        }
        ctor("(Ljava/lang/Throwable;)V") {
            val cause = r(1)
            self().native = ThrowableState(cause?.let { vm.vmToString(it) }).also { it.cause = cause; it.causeSet = true }
        }
        method("getMessage", "()Ljava/lang/String;") { retRef(state().message) }
        method("getLocalizedMessage", "()Ljava/lang/String;") { retRef(vm.callVirtual(r(0), "getMessage", "()Ljava/lang/String;")) }
        method("getCause", "()Ljava/lang/Throwable;") { val s = state(); retRef(if (s.cause === r(0)) null else s.cause) }
        method("initCause", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;") {
            val s = state()
            if (s.causeSet) vm.throwVm("java/lang/IllegalStateException", "Can't overwrite cause")
            if (r(1) === r(0)) vm.throwVm("java/lang/IllegalArgumentException", "Self-causation not permitted")
            s.cause = r(1); s.causeSet = true
            retRef(r(0))
        }
        method("toString", "()Ljava/lang/String;") {
            val msg = vm.callVirtual(r(0), "getLocalizedMessage", "()Ljava/lang/String;") as String?
            val name = (r(0) as VmObject).cls.javaName
            retRef(if (msg == null) name else "$name: $msg")
        }
        method("fillInStackTrace", "()Ljava/lang/Throwable;") { retRef(r(0)) }
        method("getStackTrace", "()[Ljava/lang/StackTraceElement;") { retRef(vm.newRefArray("java/lang/StackTraceElement", emptyArray())) }
        method("setStackTrace", "([Ljava/lang/StackTraceElement;)V") {}
        method("printStackTrace", "()V") { vm.stdout(vm.stackTraceText(r(0) as VmObject)) }
        method("printStackTrace", "(Ljava/io/PrintStream;)V") { vm.stdout(vm.stackTraceText(r(0) as VmObject)) }
        method("addSuppressed", "(Ljava/lang/Throwable;)V") { state().suppressed.add(r(1)) }
        method("getSuppressed", "()[Ljava/lang/Throwable;") { retRef(vm.newRefArray("java/lang/Throwable", state().suppressed.toTypedArray())) }
    }

    val hierarchy = listOf(
        "java/lang/Exception" to "java/lang/Throwable",
        "java/lang/Error" to "java/lang/Throwable",
        "java/lang/RuntimeException" to "java/lang/Exception",
        "java/lang/IllegalStateException" to "java/lang/RuntimeException",
        "java/lang/IllegalArgumentException" to "java/lang/RuntimeException",
        "java/lang/NumberFormatException" to "java/lang/IllegalArgumentException",
        "java/lang/NullPointerException" to "java/lang/RuntimeException",
        "java/lang/ArithmeticException" to "java/lang/RuntimeException",
        "java/lang/IndexOutOfBoundsException" to "java/lang/RuntimeException",
        "java/lang/ArrayIndexOutOfBoundsException" to "java/lang/IndexOutOfBoundsException",
        "java/lang/StringIndexOutOfBoundsException" to "java/lang/IndexOutOfBoundsException",
        "java/lang/ClassCastException" to "java/lang/RuntimeException",
        "java/lang/ArrayStoreException" to "java/lang/RuntimeException",
        "java/lang/NegativeArraySizeException" to "java/lang/RuntimeException",
        "java/lang/UnsupportedOperationException" to "java/lang/RuntimeException",
        "java/lang/IllegalMonitorStateException" to "java/lang/RuntimeException",
        "java/lang/SecurityException" to "java/lang/RuntimeException",
        "java/lang/CloneNotSupportedException" to "java/lang/Exception",
        "java/lang/InterruptedException" to "java/lang/Exception",
        "java/lang/ReflectiveOperationException" to "java/lang/Exception",
        "java/lang/ClassNotFoundException" to "java/lang/ReflectiveOperationException",
        "java/lang/NoSuchFieldException" to "java/lang/ReflectiveOperationException",
        "java/lang/NoSuchMethodException" to "java/lang/ReflectiveOperationException",
        "java/lang/InstantiationException" to "java/lang/ReflectiveOperationException",
        "java/lang/IllegalAccessException" to "java/lang/ReflectiveOperationException",
        "java/lang/LinkageError" to "java/lang/Error",
        "java/lang/NoClassDefFoundError" to "java/lang/LinkageError",
        "java/lang/ExceptionInInitializerError" to "java/lang/LinkageError",
        "java/lang/IncompatibleClassChangeError" to "java/lang/LinkageError",
        "java/lang/AbstractMethodError" to "java/lang/IncompatibleClassChangeError",
        "java/lang/NoSuchFieldError" to "java/lang/IncompatibleClassChangeError",
        "java/lang/NoSuchMethodError" to "java/lang/IncompatibleClassChangeError",
        "java/lang/InstantiationError" to "java/lang/IncompatibleClassChangeError",
        "java/lang/VirtualMachineError" to "java/lang/Error",
        "java/lang/StackOverflowError" to "java/lang/VirtualMachineError",
        "java/lang/OutOfMemoryError" to "java/lang/VirtualMachineError",
        "java/lang/InternalError" to "java/lang/VirtualMachineError",
        "java/util/NoSuchElementException" to "java/lang/RuntimeException",
        "java/util/ConcurrentModificationException" to "java/lang/RuntimeException",
        "java/util/EmptyStackException" to "java/lang/RuntimeException",
        "java/util/MissingResourceException" to "java/lang/RuntimeException",
        "java/util/concurrent/CancellationException" to "java/lang/IllegalStateException",
        "java/util/concurrent/ExecutionException" to "java/lang/Exception",
        "java/util/concurrent/TimeoutException" to "java/lang/Exception",
        "java/util/concurrent/CompletionException" to "java/lang/RuntimeException",
        "java/util/concurrent/RejectedExecutionException" to "java/lang/RuntimeException",
        "java/io/IOException" to "java/lang/Exception",
        "java/io/FileNotFoundException" to "java/io/IOException",
        "java/io/EOFException" to "java/io/IOException",
        "java/io/UncheckedIOException" to "java/lang/RuntimeException",
        "java/lang/reflect/InvocationTargetException" to "java/lang/ReflectiveOperationException",
        "java/lang/reflect/UndeclaredThrowableException" to "java/lang/RuntimeException",
        "java/lang/TypeNotPresentException" to "java/lang/RuntimeException",
        "java/lang/EnumConstantNotPresentException" to "java/lang/RuntimeException",
        "java/time/DateTimeException" to "java/lang/RuntimeException",
    )
    for ((name, parent) in hierarchy) define(name) { exception(parent) }

    define("java/lang/AssertionError") {
        exception("java/lang/Error")
        ctor("(Ljava/lang/Object;)V") {
            val detail = r(1)
            self().native = ThrowableState(vm.vmToString(detail)).also {
                if (detail is VmObject && vm.isInstance(detail, vm.loadClass("java/lang/Throwable"))) { it.cause = detail; it.causeSet = true }
            }
        }
        ctor("(Z)V") { self().native = ThrowableState(z(1).toString()) }
        ctor("(C)V") { self().native = ThrowableState(c(1).toString()) }
        ctor("(I)V") { self().native = ThrowableState(i(1).toString()) }
        ctor("(J)V") { self().native = ThrowableState(l(1).toString()) }
    }
    define("java/lang/ExceptionInInitializerError") {
        method("getException", "()Ljava/lang/Throwable;") { retRef(state().cause) }
    }
    define("java/util/NoSuchElementException") {
        ctor("(Ljava/lang/String;Ljava/lang/Throwable;)V") {
            self().native = ThrowableState(r(1) as String?).also { it.cause = r(2); it.causeSet = true }
        }
    }
    define("java/lang/ArrayIndexOutOfBoundsException") {
        ctor("(I)V") { self().native = ThrowableState("Array index out of range: ${i(1)}") }
    }
    define("java/lang/StringIndexOutOfBoundsException") {
        ctor("(I)V") { self().native = ThrowableState("String index out of range: ${i(1)}") }
    }
    define("java/lang/IndexOutOfBoundsException") {
        ctor("(I)V") { self().native = ThrowableState("Index out of range: ${i(1)}") }
    }
}

/** `printStackTrace()`: the throwable, the interpreted frames it unwound, and its causes. */
internal fun Vm.stackTraceText(t: VmObject): String = buildString {
    var cur: VmObject? = t
    var first = true
    val seen = HashSet<VmObject>()
    while (cur != null && seen.add(cur)) {
        if (!first) append("Caused by: ")
        append(vmToString(cur)).append('\n')
        val state = cur.native as? ThrowableState
        for (frame in state?.trace ?: emptyList()) append("\tat ").append(frame).append('\n')
        first = false
        cur = (state?.cause as? VmObject)?.takeIf { it !== cur }
    }
}

/** The four standard constructors every exception class has, all delegating to `Throwable`'s state. */
private fun NativeClassBuilder.exception(parent: String) {
    superName = parent
    ctor("()V") { self().native = ThrowableState(null) }
    ctor("(Ljava/lang/String;)V") { self().native = ThrowableState(r(1) as String?) }
    ctor("(Ljava/lang/String;Ljava/lang/Throwable;)V") {
        self().native = ThrowableState(r(1) as String?).also { it.cause = r(2); it.causeSet = true }
    }
    ctor("(Ljava/lang/Throwable;)V") {
        val cause = r(1)
        self().native = ThrowableState(cause?.let { vm.vmToString(it) }).also { it.cause = cause; it.causeSet = true }
    }
}
