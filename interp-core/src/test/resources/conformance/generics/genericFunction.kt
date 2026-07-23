fun <T> identity(x: T): T = x

fun <A, B> pair(a: A, b: B): String = "$a:$b"

fun box(): String {
    if (identity(42) != 42) return "FAIL int"
    if (identity("hi") != "hi") return "FAIL string"
    if (pair(1, "x") != "1:x") return "FAIL pair"
    return "OK"
}
