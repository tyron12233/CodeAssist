inline fun <reified T> typeName(): String = T::class.simpleName ?: "?"

inline fun <reified T> castOrNull(x: Any?): T? = x as? T

fun box(): String {
    if (typeName<String>() != "String") return "FAIL name"
    if (castOrNull<String>("hi") != "hi") return "FAIL cast ok"
    if (castOrNull<String>(42) != null) return "FAIL cast null"
    return "OK"
}
