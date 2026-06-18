fun sumAll(vararg xs: Int): Int {
    var s = 0
    for (x in xs) s = s + x
    return s
}
fun box(): String = if (sumAll(1, 2, 3) == 6) "OK" else "FAIL"
