fun box(): String {
    val msg = "OK"
    val f = { msg }
    return f()
}
