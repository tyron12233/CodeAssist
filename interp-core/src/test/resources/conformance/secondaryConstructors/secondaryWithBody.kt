class Accumulator {
    var total: Int = 0
    constructor(values: List<Int>) {
        for (v in values) total += v
    }
    constructor(a: Int, b: Int) : this(listOf(a, b))
}

fun box(): String {
    if (Accumulator(listOf(1, 2, 3)).total != 6) return "FAIL list"
    if (Accumulator(4, 5).total != 9) return "FAIL delegated"
    return "OK"
}
