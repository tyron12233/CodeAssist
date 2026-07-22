fun box(): String {
    var out = ""
    outer@ for (i in 1..3) {
        for (j in 1..3) {
            if (j == 2) continue@outer
            out += "$i$j "
        }
    }
    return if (out == "11 21 31 ") "OK" else "FAIL: $out"
}
