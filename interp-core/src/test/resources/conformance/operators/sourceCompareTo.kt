class Money(val cents: Int) : Comparable<Money> {
    override fun compareTo(other: Money): Int = cents - other.cents
}

fun box(): String {
    return if (Money(100) > Money(50) && Money(10) <= Money(10)) "OK" else "FAIL"
}
