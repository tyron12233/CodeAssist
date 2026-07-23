fun box(): String {
    var out = ""
    for (c in 'a'..'c') out += c
    return if (out == "abc" && 'b' in 'a'..'c') "OK" else "FAIL: $out"
}
