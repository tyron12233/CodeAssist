enum class Color { RED, GREEN }
fun box(): String = if (Color.RED.name == "RED") "OK" else "FAIL"
