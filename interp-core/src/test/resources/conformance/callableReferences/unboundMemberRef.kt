fun box(): String {
    val lengths = listOf("a", "bb", "ccc").map(String::length)
    if (lengths != listOf(1, 2, 3)) return "FAIL unbound"
    val upper = listOf("x", "y").map(String::uppercase)
    if (upper != listOf("X", "Y")) return "FAIL member"
    return "OK"
}
