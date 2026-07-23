class Wrapper<out T>(val item: T)

fun <T> firstOf(pairs: List<Pair<T, T>>): T = pairs[0].first

fun box(): String {
    val w: Wrapper<Any> = Wrapper("boxed")
    if (w.item != "boxed") return "FAIL variance"
    val nested = firstOf(listOf(1 to 2, 3 to 4))
    if (nested != 1) return "FAIL nested"
    return "OK"
}
