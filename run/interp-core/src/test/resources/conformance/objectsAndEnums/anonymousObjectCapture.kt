interface Calc {
    fun result(): Int
}

fun box(): String {
    val base = 20
    val step = 22
    val c: Calc = object : Calc {
        override fun result(): Int = base + step
    }
    return if (c.result() == 42) "OK" else "FAIL: ${c.result()}"
}
