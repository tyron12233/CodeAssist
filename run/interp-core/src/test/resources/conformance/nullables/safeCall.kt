fun box(): String {
    val s: String? = null
    val len = s?.length
    return if (len == null) "OK" else "FAIL"
}
