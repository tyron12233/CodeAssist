fun box(): String = when (3) {
    in 1..5 -> "OK"
    else -> "FAIL"
}
