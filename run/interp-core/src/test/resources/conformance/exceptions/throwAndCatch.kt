fun box(): String {
    return try {
        throw RuntimeException("boom")
    } catch (e: RuntimeException) {
        "OK"
    }
}
