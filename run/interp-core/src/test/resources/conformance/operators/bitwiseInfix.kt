fun box(): String {
    if ((0b1100 and 0b1010) != 0b1000) return "FAIL and"
    if ((0b1100 or 0b1010) != 0b1110) return "FAIL or"
    if ((0b1100 xor 0b1010) != 0b0110) return "FAIL xor"
    return "OK"
}
