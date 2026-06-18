data class Names(val a: String, val b: String)
fun box(): String {
    val (a, b) = Names("O", "K")
    return a + b
}
