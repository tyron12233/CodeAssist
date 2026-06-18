open class Animal(val name: String) {
    fun describe(): String = name
}
class Dog(name: String) : Animal(name)
fun box(): String = if (Dog("Rex").describe() == "Rex") "OK" else "FAIL"
