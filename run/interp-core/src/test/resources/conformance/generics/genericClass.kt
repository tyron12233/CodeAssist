class Box<T>(val value: T) {
    fun get(): T = value
}

fun box(): String {
    val a = Box(42)
    val b = Box("hello")
    if (a.get() != 42) return "FAIL int box"
    if (b.value != "hello") return "FAIL string box"
    return "OK"
}
