fun box(): String {
    val a = 0xF0L and 0xFFL
    if (a != 0xF0L) return "FAIL and"
    if ((1L shl 40) != 1099511627776L) return "FAIL shl"
    if ((0xFFL or 0x100L) != 0x1FFL) return "FAIL or"
    return "OK"
}
