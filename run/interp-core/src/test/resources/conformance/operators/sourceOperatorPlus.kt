data class V(val n: Int) {
    operator fun plus(other: V): V = V(n + other.n)
}

fun box(): String {
    val r = V(20) + V(22)
    return if (r.n == 42) "OK" else "FAIL: ${r.n}"
}
