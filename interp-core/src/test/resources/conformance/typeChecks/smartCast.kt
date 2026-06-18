fun box(): String {
    val x: Any = "OK"
    if (x is String) return x
    return "FAIL"
}
