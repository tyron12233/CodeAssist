interface Greeter { fun greet(): String = "OK" }
class English : Greeter
fun box(): String = English().greet()
