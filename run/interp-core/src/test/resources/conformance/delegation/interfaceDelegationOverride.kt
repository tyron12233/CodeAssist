interface Counter {
    fun next(): Int
    fun label(): String
}

class BaseCounter : Counter {
    private var n = 0
    override fun next(): Int { n += 1; return n }
    override fun label(): String = "base"
}

class LabeledCounter(c: Counter) : Counter by c {
    override fun label(): String = "labeled"
}

fun box(): String {
    val lc = LabeledCounter(BaseCounter())
    if (lc.next() != 1) return "FAIL delegated next"
    if (lc.next() != 2) return "FAIL delegated next 2"
    if (lc.label() != "labeled") return "FAIL override"
    return "OK"
}
