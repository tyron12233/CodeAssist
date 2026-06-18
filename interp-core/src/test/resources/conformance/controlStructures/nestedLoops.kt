fun box(): String {
    var c = 0
    for (a in listOf(1, 2)) for (b in listOf(1, 2)) c = c + 1
    return if (c == 4) "OK" else "FAIL"
}
