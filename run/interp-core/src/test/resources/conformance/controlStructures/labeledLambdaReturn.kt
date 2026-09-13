// `return@label` from a lambda. The label names the lambda the return belongs to; a BARE `return` inside an
// inline lambda is Kotlin's NON-LOCAL return and belongs to the enclosing function instead. Both spellings
// lower to the same node, so the label has to travel with it — without it a `return@forEach` unwound past
// its lambda and surfaced as a raw "ReturnSignal".

fun firstEven(xs: List<Int>): Int {
    xs.forEach { if (it % 2 == 0) return it }   // non-local: returns from firstEven
    return -1
}

fun sumSkippingOdds(xs: List<Int>): Int {
    var total = 0
    xs.forEach {
        if (it % 2 != 0) return@forEach          // continue-like: ends this iteration only
        total += it
    }
    return total
}

fun labelled(xs: List<Int>): Int {
    var seen = 0
    xs.forEach outer@{
        if (it > 2) return@outer                 // an explicit label on the lambda
        seen += it
    }
    return seen
}

fun mapped(xs: List<Int>): List<Int> = xs.map {
    if (it < 0) return@map 0                     // the labelled return supplies the lambda's VALUE
    it * 2
}

fun box(): String {
    if (firstEven(listOf(1, 3, 4, 5)) != 4) return "FAIL non-local return"
    if (firstEven(listOf(1, 3, 5)) != -1) return "FAIL non-local fallthrough"
    if (sumSkippingOdds(listOf(1, 2, 3, 4)) != 6) return "FAIL return@forEach"
    if (labelled(listOf(1, 2, 5)) != 3) return "FAIL explicit label"
    if (mapped(listOf(-1, 2)) != listOf(0, 4)) return "FAIL return@map value"
    // A labelled return nested inside another lambda must unwind to ITS lambda, not the inner one.
    var hits = 0
    listOf(1, 2, 3).forEach {
        listOf(10, 20).forEach inner@{ y ->
            if (y == 20) return@inner
            hits += 1
        }
        if (it == 2) return@forEach
        hits += 100
    }
    if (hits != 203) return "FAIL nested labels: " + hits
    return "OK"
}
