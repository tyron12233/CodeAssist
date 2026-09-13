// `runCatching` and the `Result` readers. All of them are inline on a value class, so none has a JVM method
// the dispatcher can reach — the interpreter has to model the whole family or none of it works.

// A stdlib exception type on purpose: reading `message` off an INTERPRETED subclass of a library exception
// is a separate gap in the interpreter, and this case is about `runCatching`, not about that.
fun parse(text: String): Int = if (text == "bad") throw IllegalStateException("nope") else text.length

fun box(): String {
    // Success and failure, read back the two most common ways.
    if (runCatching { parse("abcd") }.getOrDefault(-1) != 4) return "FAIL success getOrDefault"
    if (runCatching { parse("bad") }.getOrDefault(-1) != -1) return "FAIL failure getOrDefault"
    if (runCatching { parse("abc") }.getOrNull() != 3) return "FAIL success getOrNull"
    if (runCatching { parse("bad") }.getOrNull() != null) return "FAIL failure getOrNull"

    // isSuccess / isFailure / exceptionOrNull.
    if (!runCatching { parse("x") }.isSuccess) return "FAIL isSuccess"
    if (!runCatching { parse("bad") }.isFailure) return "FAIL isFailure"
    val thrown = runCatching { parse("bad") }.exceptionOrNull()
    if (thrown == null) return "FAIL exceptionOrNull null"
    if (thrown.message != "nope") return "FAIL exception message: " + thrown.message

    // getOrElse and fold take the failure as their argument.
    if (runCatching { parse("bad") }.getOrElse { -2 } != -2) return "FAIL getOrElse"
    if (runCatching { parse("ab") }.getOrElse { -2 } != 2) return "FAIL getOrElse success"
    val folded = runCatching { parse("bad") }.fold({ "ok" }, { "err:" + it.message })
    if (folded != "err:nope") return "FAIL fold failure: " + folded
    if (runCatching { parse("abcde") }.fold({ it.toString() }, { "err" }) != "5") return "FAIL fold success"

    // onSuccess / onFailure run one side and return the Result unchanged, so they chain.
    var seen = ""
    val chained = runCatching { parse("bad") }.onSuccess { seen = "s" }.onFailure { seen = "f" }
    if (seen != "f") return "FAIL onFailure"
    if (chained.getOrDefault(9) != 9) return "FAIL chain returns the Result"
    if (runCatching { parse("ab") }.onSuccess { seen = "s2" }.getOrNull() != 2) return "FAIL onSuccess chain"
    if (seen != "s2") return "FAIL onSuccess body"

    // recover replaces a failure with a value, leaves a success alone.
    if (runCatching { parse("bad") }.recover { 7 }.getOrNull() != 7) return "FAIL recover"
    if (runCatching { parse("abc") }.recover { 7 }.getOrNull() != 3) return "FAIL recover success"

    // The receiver form: `x.runCatching { }` binds x as the block's `this`.
    if ("hello".runCatching { length }.getOrDefault(0) != 5) return "FAIL receiver form"

    // A `return` crossing the block is control flow, not a failure it should swallow.
    if (earlyReturn() != "returned") return "FAIL return through runCatching"

    return "OK"
}

fun earlyReturn(): String {
    runCatching { return "returned" }
    return "fell through"
}
