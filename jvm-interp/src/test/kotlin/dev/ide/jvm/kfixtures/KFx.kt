package dev.ide.jvm.kfixtures

import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine

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

/** Applies a `(Char) -> Boolean` predicate to each character of [s] from INTERPRETED code. Deliberately NOT
 *  `inline`, so `predicate` is a genuine `Function1`: `predicate(c)` boxes the char (bridged `Character.valueOf`)
 *  and passes it through the erased `Function1.invoke(Object)`, so the specialized impl `(C)Z` receives a real
 *  `Character` at its `char` parameter (an `inline` receiver would splice the body and never box). */
private fun charFilter(s: String, predicate: (Char) -> Boolean): String = buildString {
    for (c in s) if (predicate(c)) append(c)
}

/** Reproduces the argument-side counterpart of [boolViaBridge]: a specialized primitive-arg lambda invoked
 *  with a real wrapper. Regression for `ClassCastException: java.lang.Character cannot be cast to
 *  java.lang.Integer` when seeding the impl's `char` local. */
fun filterLetters(s: String): String = charFilter(s) { it in 'a'..'z' }

/** An exception declared by interpreted code over a REAL supertype: instances cross the bridge as generated
 *  peers, which is what makes them catchable-by-the-wrong-name (see [rethrownByRealCode]). */
class MyKtException : Throwable("just an exception")

/** Real code holds an interpreted throwable and throws it back at the interpreted frame: `Optional.orElseThrow`
 *  invokes the supplier (an interpreted lambda whose result crosses out as a peer) and throws the result. The
 *  `catch` naming the INTERPRETED type must still match. */
fun rethrownByRealCode(): String =
    try {
        java.util.Optional.empty<String>().orElseThrow { MyKtException() }
    } catch (e: MyKtException) {
        "Caught ${e.message}"
    }

/** The same rethrow caught by a REAL supertype of the interpreted exception, which must keep working. */
fun rethrownByRealCodeCaughtAsThrowable(): String =
    try {
        java.util.Optional.empty<String>().orElseThrow { MyKtException() }
    } catch (e: Throwable) {
        "Caught ${e.message}"
    }

/** An interpreted exception must NOT match a `catch` of a real type it does not extend (`MyKtException` is a
 *  `Throwable`, not an `Exception`), so the peer's own class name cannot be what the match consults. */
fun rethrownByRealCodeNotAnException(): String =
    try {
        try {
            java.util.Optional.empty<String>().orElseThrow { MyKtException() }
        } catch (e: Exception) {
            "wrongly caught as Exception"
        }
    } catch (e: Throwable) {
        "propagated to Throwable"
    }

private suspend fun resumeWithAnException(): String =
    try {
        kotlin.coroutines.suspendCoroutine<Unit> { continuation ->
            continuation.resumeWithException(MyKtException())
        }
        "not caught"
    } catch (e: MyKtException) {
        "Caught ${e.message}"
    }

/** The reported shape: `suspendCoroutine { it.resumeWithException(e) }` resumes SYNCHRONOUSLY, so the stdlib's
 *  `SafeContinuation.getOrThrow()` (real, bridged) throws the interpreted exception straight back into the
 *  interpreted frame's `try`. Driven with a bare stdlib continuation so no kotlinx-coroutines is needed. */
fun suspendCoroutineResumeWithException(): String {
    var out = "no result"
    val block: suspend () -> String = ::resumeWithAnException
    block.startCoroutine(object : kotlin.coroutines.Continuation<String> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<String>) {
            out = result.getOrElse { "escaped as ${it.javaClass.simpleName}" }
        }
    })
    return out
}
