fun apply2(f: (Int) -> Int, x: Int): Int = f(f(x))
fun box(): String = if (apply2({ it + 1 }, 0) == 2) "OK" else "FAIL"
