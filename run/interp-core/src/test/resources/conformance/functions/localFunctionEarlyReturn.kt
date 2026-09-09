fun box(): String {
    fun classify(n: Int): String {
        if (n < 0) return "neg"
        if (n == 0) return "zero"
        return "pos"
    }
    if (classify(-5) != "neg") return "FAIL neg"
    if (classify(0) != "zero") return "FAIL zero"
    if (classify(7) != "pos") return "FAIL pos"
    return "OK"
}
