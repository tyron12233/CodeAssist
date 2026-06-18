fun box(): String {
    var i = 0
    do { i = i + 1 } while (i < 3)
    return if (i == 3) "OK" else "FAIL"
}
