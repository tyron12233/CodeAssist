fun box(): String {
    var s = 0
    listOf(10, 20, 30).forEach { s = s + it }
    return if (s == 60) "OK" else "FAIL"
}
