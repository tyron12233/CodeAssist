fun box(): String {
    val any: Any = 42
    val s = any as? String
    if (s != null) return "FAIL should be null"
    val n = any as? Int
    if (n != 42) return "FAIL should be 42"
    return "OK"
}
