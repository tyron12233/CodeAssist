interface Greeter {
    fun greet(): String
}

fun box(): String {
    val g: Greeter = object : Greeter {
        override fun greet(): String = "OK"
    }
    return g.greet()
}
