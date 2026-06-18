fun box(): String {
    var c = 0
    for (i in 0..10 step 2) c = c + 1
    return if (c == 6) "OK" else "FAIL"
}
