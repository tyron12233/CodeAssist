fun box(): String {
    var s = 0
    for (i in listOf(1, 2, 3, 4)) {
        if (i == 3) break
        if (i == 1) continue
        s = s + i
    }
    return if (s == 2) "OK" else "FAIL"
}
