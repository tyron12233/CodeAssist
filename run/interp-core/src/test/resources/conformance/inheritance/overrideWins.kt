open class Shape { open fun area(): Int = 0 }
class Square(val side: Int) : Shape() { override fun area(): Int = side * side }
fun box(): String = if (Square(4).area() == 16) "OK" else "FAIL"
