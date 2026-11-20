package dev.ide.jvm.kfixtures

class KOuter {
    class KNested
}

/** The Kotlin names of classes held as literals, which the Kotlin runtime reads off the `Class` it is given. */
fun kotlinClassNames(): String {
    val anonymous = object {}
    return listOf(
        KOuter::class.qualifiedName,
        KOuter.KNested::class.qualifiedName,
        KOuter.KNested::class.simpleName,
        anonymous::class.qualifiedName,
        String::class.qualifiedName,
    ).joinToString(",")
}
