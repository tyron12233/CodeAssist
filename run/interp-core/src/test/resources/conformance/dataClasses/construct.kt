data class Point(val x: Int, val y: Int)
fun box(): String {
    val p = Point(1, 2)
    if (p.x != 1) return "FAIL"
    if (p.y != 2) return "FAIL"
    return "OK"
}
