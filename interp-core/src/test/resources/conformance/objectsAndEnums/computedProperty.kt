class Circle(val r: Int) {
    val area: Int get() = r * r * 3
}

fun box(): String {
    val c = Circle(2)
    return if (c.area == 12) "OK" else "FAIL: ${c.area}"
}
