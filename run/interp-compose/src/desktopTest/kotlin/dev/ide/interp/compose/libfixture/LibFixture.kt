package dev.ide.interp.compose.libfixture

/**
 * A stand-in "project dependency": VmLibraryExecutorTest treats this package as NOT host-loadable (the test
 * seam), so these classes execute in the bytecode VM from their class bytes — the exact shape of a library
 * that exists only in a downloaded jar, exercising constructors, defaults, properties, objects, varargs,
 * lambdas, extensions, and a bridged (host-class) return value.
 */
class Counter(val start: Int = 10) {
    var value: Int = start
    fun add(n: Int): Int { value += n; return value }
    fun label(prefix: String = "v"): String = "$prefix$value"
}

object Registry {
    val name: String = "registry"
    fun describe(counter: Counter): String = "reg:${counter.value}"
}

fun makeCounter(start: Int): Counter = Counter(start)

fun sum(vararg xs: Int): Int = xs.sum()

fun apply3(f: (Int) -> Int): Int = f(3)

fun Counter.doubled(): Int = value * 2

/** Constructs a HOST class (bridged inside the VM) and returns it — the icons-shaped flow, where library
 *  code builds bundled-runtime values the interpreter then uses directly. */
fun buildText(): StringBuilder = StringBuilder("lib")
