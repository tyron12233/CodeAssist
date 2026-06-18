fun box(): String {
    val x: Any = 42
    return when (x) {
        is Int -> "OK"
        else -> "FAIL"
    }
}
