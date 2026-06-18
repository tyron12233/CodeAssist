fun box(): String {
    val x: Any = "hello"
    return if (x is String) "OK" else "FAIL"
}
