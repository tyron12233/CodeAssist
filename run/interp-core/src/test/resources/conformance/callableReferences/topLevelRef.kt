fun double(x: Int): Int = x * 2

fun box(): String {
    val f = ::double
    if (f(21) != 42) return "FAIL invoke"
    val mapped = listOf(1, 2, 3).map(::double)
    if (mapped != listOf(2, 4, 6)) return "FAIL map"
    return "OK"
}
