fun box(): String {
    fun square(x: Int): Int = x * x
    fun add(a: Int, b: Int): Int = a + b
    if (square(5) != 25) return "FAIL square"
    if (add(3, 4) != 7) return "FAIL add"
    return "OK"
}
