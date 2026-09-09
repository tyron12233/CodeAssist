fun box(): String {
    var total = 0
    fun accumulate(x: Int) {
        total += x
    }
    accumulate(3)
    accumulate(4)
    if (total != 7) return "FAIL closure capture"
    return "OK"
}
