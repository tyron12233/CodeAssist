class Box(val a: Int, val b: Int, val c: Int) {
    constructor(a: Int, b: Int) : this(a, b, 0)
    constructor(a: Int) : this(a, 0)
}

fun box(): String {
    val one = Box(7)
    if (one.a != 7 || one.b != 0 || one.c != 0) return "FAIL one"
    val two = Box(7, 8)
    if (two.a != 7 || two.b != 8 || two.c != 0) return "FAIL two"
    return "OK"
}
