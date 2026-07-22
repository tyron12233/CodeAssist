class Point(val x: Int, val y: Int) {
    constructor(v: Int) : this(v, v)
}

fun box(): String {
    val p = Point(3)
    if (p.x != 3 || p.y != 3) return "FAIL"
    return "OK"
}
