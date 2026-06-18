fun adder(n: Int): (Int) -> Int = { it + n }
fun box(): String {
    val add5 = adder(5)
    return if (add5(3) == 8) "OK" else "FAIL"
}
