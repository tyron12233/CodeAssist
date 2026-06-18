fun box(): String {
    var i = 0
    while (i < 3) i = i + 1
    return if (i == 3) "OK" else "FAIL"
}
