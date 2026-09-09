sealed class Expr
class Num(val value: Int) : Expr()
class Add(val left: Int, val right: Int) : Expr()
fun eval(e: Expr): Int = when (e) {
    is Num -> e.value
    is Add -> e.left + e.right
    else -> -1
}
fun box(): String = if (eval(Num(7)) + eval(Add(2, 3)) == 12) "OK" else "FAIL"
