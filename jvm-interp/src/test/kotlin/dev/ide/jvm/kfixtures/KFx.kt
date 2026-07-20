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
