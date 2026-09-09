fun box(): String {
    var s = 0
    for (x in listOf(1, 2, 3)) s = s + x
    return if (s == 6) "OK" else "FAIL"
}
