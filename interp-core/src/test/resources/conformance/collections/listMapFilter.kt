fun box(): String {
    val r = listOf(1, 2, 3, 4).filter { it % 2 == 0 }.map { it * 10 }
    return if (r == listOf(20, 40)) "OK" else "FAIL"
}
