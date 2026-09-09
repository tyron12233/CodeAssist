fun describe(x: Any?): String = when (x) {
    is List<*> -> "list"
    is Map<*, *> -> "map"
    else -> "other"
}

fun box(): String {
    if (describe(listOf(1, 2)) != "list") return "FAIL list"
    if (describe(mapOf(1 to 2)) != "map") return "FAIL map"
    if (describe(42) != "other") return "FAIL other"
    return "OK"
}
