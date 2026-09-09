fun box(): String {
    return try {
        val x = 1 / 0
        "FAIL"
    } catch (e: ArithmeticException) {
        "OK"
    }
}
