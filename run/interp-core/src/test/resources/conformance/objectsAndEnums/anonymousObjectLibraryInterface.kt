fun box(): String {
    val xs = listOf(3, 1, 2)
    val sorted = xs.sortedWith(object : Comparator<Int> {
        override fun compare(a: Int, b: Int): Int = a - b
    })
    return if (sorted == listOf(1, 2, 3)) "OK" else "FAIL: $sorted"
}
