data class Boxed(val v: Int)
fun box(): String {
    val a = Boxed(1)
    val b = a.copy(v = 2)
    if (b.v != 2) return "FAIL"
    if (a.v != 1) return "FAIL"
    return "OK"
}
