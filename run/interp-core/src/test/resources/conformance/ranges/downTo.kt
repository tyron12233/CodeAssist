fun box(): String {
    var s = ""
    for (i in 3 downTo 1) s = s + i
    return if (s == "321") "OK" else "FAIL"
}
