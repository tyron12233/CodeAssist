fun box(): String {
    val m = mutableListOf<Int>()
    m.add(1)
    m.add(2)
    return if (m.size == 2) "OK" else "FAIL"
}
