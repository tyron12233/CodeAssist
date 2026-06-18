open class A(val a: Int) { fun base(): Int = a }
open class B(a: Int, val b: Int) : A(a)
class C(a: Int, b: Int, val c: Int) : B(a, b)
fun box(): String {
    val obj = C(1, 2, 3)
    return if (obj.base() + obj.b + obj.c == 6) "OK" else "FAIL"
}
