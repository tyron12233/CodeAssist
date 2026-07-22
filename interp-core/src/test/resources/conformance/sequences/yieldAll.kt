fun box(): String {
    val seq = sequence {
        yield(0)
        yieldAll(listOf(1, 2, 3))
        yield(4)
    }
    if (seq.toList() != listOf(0, 1, 2, 3, 4)) return "FAIL yieldAll"
    return "OK"
}
