interface Greeter {
    fun greet(): String
    val name: String
}

class RealGreeter(override val name: String) : Greeter {
    override fun greet(): String = "hi $name"
}

class Wrapper(g: Greeter) : Greeter by g

fun box(): String {
    val w = Wrapper(RealGreeter("bob"))
    if (w.greet() != "hi bob") return "FAIL greet"
    if (w.name != "bob") return "FAIL name"
    return "OK"
}
