open class Shape { open fun area(): Int = 0 }
class Square(val side: Int) : Shape() { override fun area(): Int = side * side }
fun areaOf(s: Shape): Int = s.area()
fun box(): String = if (areaOf(Square(5)) == 25) "OK" else "FAIL"
