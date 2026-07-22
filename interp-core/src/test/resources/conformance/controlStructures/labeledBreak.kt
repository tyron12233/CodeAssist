fun box(): String {
    var out = ""
    outer@ for (i in 1..3) {
        for (j in 1..3) {
            if (i == 2 && j == 2) break@outer
            out += "$i$j "
        }
    }
    return if (out == "11 12 13 21 ") "OK" else "FAIL: $out"
}
