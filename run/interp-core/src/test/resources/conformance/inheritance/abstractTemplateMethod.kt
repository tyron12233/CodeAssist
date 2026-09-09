abstract class Vehicle(val wheels: Int) {
    abstract fun sound(): String
    fun honk(): String = sound() + wheels
}
class Car : Vehicle(4) {
    override fun sound(): String = "vroom"
}
fun box(): String = if (Car().honk() == "vroom4") "OK" else "FAIL"
