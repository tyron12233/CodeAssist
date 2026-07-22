fun box(): String {
    val seq = sequence {
        yield(1)
        yield(2)
        yield(3)
    }
    if (seq.toList() != listOf(1, 2, 3)) return "FAIL toList"
    return "OK"
}
