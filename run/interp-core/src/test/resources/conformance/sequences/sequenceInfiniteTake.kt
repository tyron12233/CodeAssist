fun box(): String {
    val naturals = sequence {
        var n = 1
        while (true) {
            yield(n)
            n += 1
        }
    }
    if (naturals.take(5).toList() != listOf(1, 2, 3, 4, 5)) return "FAIL take"
    return "OK"
}
