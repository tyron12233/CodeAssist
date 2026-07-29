package dev.ide.jvm.kfixtures

/** An inline value class, as Compose uses for Dp/Color/TextUnit: its members compile to mangled static
 *  `-impl` methods over the unboxed underlying value. */
@JvmInline
value class Dp(val value: Float) {
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    fun scaled(by: Float): Dp = Dp(value * by)
}

/** A data class: generated equals/hashCode/toString/componentN/copy, exercised through interpreted bytecode. */
data class Point(val x: Int, val y: Int) {
    fun manhattan(): Int = kotlin.math.abs(x) + kotlin.math.abs(y)
}

fun greetFull(name: String, punct: String): String = "Hello, $name$punct"

fun greetDefault(name: String, punct: String = "!"): String = "Hi, $name$punct"

/** Calls a defaulted function, so this function's bytecode invokes the `$default` synthetic. */
fun greetWithDefault(name: String): String = greetDefault(name)

fun dpAdd(a: Float, b: Float): Float = (Dp(a) + Dp(b)).value

fun dpScaled(a: Float, by: Float): Float = Dp(a).scaled(by).value

fun pointToString(x: Int, y: Int): String = Point(x, y).toString()

fun pointEquals(x: Int, y: Int): Boolean = Point(x, y) == Point(x, y) && Point(x, y) != Point(y, x + 1)

fun pointHashStable(x: Int, y: Int): Boolean = Point(x, y).hashCode() == Point(x, y).hashCode()

fun manhattan(x: Int, y: Int): Int = Point(x, y).manhattan()

/** `copy` with a named argument compiles to a `copy$default` call. */
fun copyX(x: Int, y: Int, nx: Int): String = Point(x, y).copy(x = nx).toString()

fun classify(n: Int): String = when {
    n < 0 -> "neg"
    n == 0 -> "zero"
    else -> "pos"
}

fun elvis(x: Int, fallback: Int): Int {
    val v: Int? = if (x > 0) x else null
    return v ?: fallback
}

fun higherOrder(n: Int): Int {
    val twice: (Int) -> Int = { it * 2 }
    return twice(n) + 1
}

/** A reified inline whose body is `T::class.java` (OperationKind JAVA_CLASS) — uncallable reflectively;
 *  exercises the VM reification transform's class-literal rewrite. */
inline fun <reified T> classOf(): Class<T> = T::class.java

/** A reified inline whose body casts with `as` (OperationKind AS) — exercises the CHECKCAST rewrite. */
inline fun <reified T> castTo(x: Any?): T = x as T

/** A `boolean`-returning lambda passed to REAL (bridged) code that invokes it: `Optional.orElseGet(Supplier)`.
 *  Kotlin compiles `{ false }` to an `invokedynamic` whose implementation method returns primitive `Z`, adapted
 *  to the erased `Supplier.get():Object` SAM; when the real `orElseGet` calls the proxied supplier, the VM must
 *  box the result as `java.lang.Boolean` (metafactory's contract), not `Integer` (the interpreter's `Int`
 *  representation shared by boolean/byte/char/short), or a Compose `CompositionLocal<Boolean>` default-factory
 *  read `(Boolean) …` throws a ClassCastException. `int…` is the already-correct control. */
fun boolViaBridge(): Any? = java.util.Optional.empty<Boolean>().orElseGet { false }
fun byteViaBridge(): Any? = java.util.Optional.empty<Byte>().orElseGet { 7.toByte() }
fun charViaBridge(): Any? = java.util.Optional.empty<Char>().orElseGet { 'Q' }
fun shortViaBridge(): Any? = java.util.Optional.empty<Short>().orElseGet { 9.toShort() }
fun intViaBridge(): Any? = java.util.Optional.empty<Int>().orElseGet { 42 }
