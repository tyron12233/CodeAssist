package dev.ide.fakecompose

/**
 * A compiled mirror of the AndroidX Activity Result API, small enough to exercise the editor's lambda-parameter
 * inference through a generic contract + a SAM callback (the `registerForActivityResult` shape) from the binary
 * (`@Metadata`) path. `FakeStartActForResult` declares NO type arguments of its own — its element type
 * `FakeActResult` is only recoverable by projecting it onto `FakeActContract`, which is what the sibling-argument
 * generic inference under test must do. Compiled into the test classpath; packaged into a jar by the tests.
 */
class FakeActIntent {
    fun getStringExtra(name: String): String? = null
    fun getLongExtra(name: String, def: Long): Long = def
    fun getIntExtra(name: String, def: Int): Int = def
}

open class FakeActContract<I, O>

class FakeActResult {
    val data: FakeActIntent? = null
}

class FakeStartActForResult : FakeActContract<FakeActIntent, FakeActResult>()

fun interface FakeActivityCallback<O> {
    fun onActivityResult(result: O)
}

/** Plain function-type callback — isolates the sibling-argument generic projection (no SAM involved). */
fun <I, O> regFnCallback(contract: FakeActContract<I, O>, callback: (O) -> Unit) {}

/** SAM callback — the `registerForActivityResult(contract, ActivityResultCallback<O>)` shape. */
fun <I, O> regSamCallback(contract: FakeActContract<I, O>, callback: FakeActivityCallback<O>) {}
