inline fun <reified T> isType(x: Any?): Boolean = x is T

fun box(): String {
    if (!isType<String>("hi")) return "FAIL string true"
    if (isType<String>(42)) return "FAIL int false"
    if (!isType<Int>(42)) return "FAIL int true"
    return "OK"
}
