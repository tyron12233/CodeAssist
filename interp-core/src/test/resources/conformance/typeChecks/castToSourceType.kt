open class Animal(val name: String)
class Dog(name: String) : Animal(name) {
    fun bark(): String = "woof"
}

fun box(): String {
    val a: Animal = Dog("rex")
    val d = a as Dog
    if (d.bark() != "woof") return "FAIL downcast"
    val maybe = a as? Dog
    if (maybe == null) return "FAIL safe downcast"
    val notDog: Animal = Animal("generic")
    if ((notDog as? Dog) != null) return "FAIL safe should be null"
    return "OK"
}
