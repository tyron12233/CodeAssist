fun box(): String {
    fun inner(): String = "OK"
    return inner()
}
