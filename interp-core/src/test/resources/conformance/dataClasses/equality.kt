data class K(val v: Int)
fun box(): String {
    if (K(1) != K(1)) return "FAIL"
    if (K(1) == K(2)) return "FAIL"
    return "OK"
}
