fun box(): String {
    var s = 0
    listOf(1, 2, 3).forEach { s = s + it }
    return if (s == 6) "OK" else "FAIL"
}
