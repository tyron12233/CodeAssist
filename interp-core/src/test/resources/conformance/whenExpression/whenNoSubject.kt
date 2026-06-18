fun box(): String {
    val n = 5
    return when {
        n < 0 -> "FAIL"
        n > 0 -> "OK"
        else -> "FAIL"
    }
}
