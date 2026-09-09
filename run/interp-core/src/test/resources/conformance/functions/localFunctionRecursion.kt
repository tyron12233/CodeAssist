fun box(): String {
    fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)
    if (fib(10) != 55) return "FAIL fib"
    return "OK"
}
