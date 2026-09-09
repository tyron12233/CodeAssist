fun box(): String {
    val a = "O".let { it + "K" }
    val b = a.takeIf { it == "OK" }
    val c = a.takeUnless { it == "NO" }
    val d = a.also { }
    return if (a == "OK" && b == "OK" && c == "OK" && d == "OK") "OK" else "FAIL"
}
