import kotlin.reflect.KProperty

class Const(private val v: String) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = v
}

fun box(): String {
    val x: String by Const("OK")
    return x
}
