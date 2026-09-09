fun box(): String {
    val mixed: List<Any> = listOf(1, "a", 2, "b")
    val strings = mixed.filterIsInstance<String>()
    if (strings != listOf("a", "b")) return "FAIL filterIsInstance"
    return "OK"
}
