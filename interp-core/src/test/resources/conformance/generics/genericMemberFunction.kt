class Container<T>(private val items: MutableList<T> = mutableListOf()) {
    fun add(item: T): Container<T> {
        items.add(item)
        return this
    }
    fun <R> mapFirst(transform: (T) -> R): R = transform(items[0])
    fun size(): Int = items.size
}

fun box(): String {
    val c = Container<Int>()
    c.add(10).add(20)
    if (c.size() != 2) return "FAIL size"
    val doubled = c.mapFirst { it * 2 }
    if (doubled != 20) return "FAIL mapFirst"
    return "OK"
}
