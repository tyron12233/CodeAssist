class Multiplier(val factor: Int) {
    fun apply(x: Int): Int = x * factor
}

fun box(): String {
    val m = Multiplier(3)
    val f = m::apply
    if (f(4) != 12) return "FAIL invoke"
    if (listOf(1, 2, 3).map(m::apply) != listOf(3, 6, 9)) return "FAIL map"
    return "OK"
}
