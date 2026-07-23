enum class Planet { EARTH, MARS, VENUS }

fun box(): String {
    val all = enumValues<Planet>()
    if (all.size != 3) return "FAIL size"
    if (all[1].name != "MARS") return "FAIL name"
    val v = enumValueOf<Planet>("VENUS")
    if (v.name != "VENUS") return "FAIL valueOf"
    if (v.ordinal != 2) return "FAIL ordinal"
    return "OK"
}
