open class Base(val x: Int) {
    val doubled: Int = x * 2
}
class Derived(x: Int, val y: Int) : Base(x)
fun box(): String {
    val d = Derived(3, 5)
    return if (d.x + d.doubled + d.y == 14) "OK" else "FAIL"
}
