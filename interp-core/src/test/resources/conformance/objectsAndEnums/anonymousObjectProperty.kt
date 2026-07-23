interface Named {
    val label: String
}

fun box(): String {
    val suffix = "K"
    val n: Named = object : Named {
        override val label: String = "O" + suffix
    }
    return n.label
}
