fun box(): String {
    if ((1 shl 4) != 16) return "FAIL shl"
    if ((256 shr 2) != 64) return "FAIL shr"
    if ((-1 ushr 28) != 15) return "FAIL ushr"
    if (5.inv() != -6) return "FAIL inv"
    val flags = (1 shl 0) or (1 shl 2)
    if (flags != 5) return "FAIL flags"
    return "OK"
}
