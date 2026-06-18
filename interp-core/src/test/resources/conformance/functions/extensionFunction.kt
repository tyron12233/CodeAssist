fun Int.doubled(): Int = this * 2
fun box(): String = if (3.doubled() == 6) "OK" else "FAIL"
