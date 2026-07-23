fun <T> List<T>.secondOrNull(): T? = if (size >= 2) this[1] else null

fun box(): String {
    val xs = listOf(10, 20, 30)
    if (xs.secondOrNull() != 20) return "FAIL second"
    val empty = listOf<Int>()
    if (empty.secondOrNull() != null) return "FAIL empty"
    return "OK"
}
