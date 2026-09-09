class Outer {
    class Inner(val v: String)
}

fun box(): String {
    return Outer.Inner("OK").v
}
