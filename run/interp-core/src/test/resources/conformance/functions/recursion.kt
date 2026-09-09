fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)
fun box(): String = if (fib(7) == 13) "OK" else "FAIL"
