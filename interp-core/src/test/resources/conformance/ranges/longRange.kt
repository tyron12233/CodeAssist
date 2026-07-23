fun box(): String {
    var sum = 0L
    for (i in 1L..5L) sum += i
    return if (sum == 15L) "OK" else "FAIL: $sum"
}
