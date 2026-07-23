fun <T : Comparable<T>> larger(a: T, b: T): T = if (a >= b) a else b

fun box(): String {
    if (larger(3, 7) != 7) return "FAIL int"
    if (larger("apple", "banana") != "banana") return "FAIL string"
    return "OK"
}
