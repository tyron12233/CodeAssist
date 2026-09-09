data class Wrap(val v: Int)

fun box(): String {
    val make = ::Wrap
    val w = make(5)
    if (w.v != 5) return "FAIL invoke"
    val wraps = listOf(1, 2).map(::Wrap)
    if (wraps != listOf(Wrap(1), Wrap(2))) return "FAIL map"
    return "OK"
}
