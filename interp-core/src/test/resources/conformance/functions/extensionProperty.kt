class Boxed(val v: Int)

val Boxed.doubled: Int get() = v * 2

fun box(): String {
    val b = Boxed(21)
    return if (b.doubled == 42) "OK" else "FAIL: ${b.doubled}"
}
