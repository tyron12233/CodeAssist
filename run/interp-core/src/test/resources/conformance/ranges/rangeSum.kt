fun box(): String {
    var s = 0
    for (i in 1..5) s = s + i
    return if (s == 15) "OK" else "FAIL"
}
