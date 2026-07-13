package dev.ide.fakecompose

import androidx.compose.runtime.Composable
import kotlin.reflect.KProperty

/** Mirrors Compose's `androidx.compose.runtime.{getValue,setValue}`: the `by`-delegation operators for a
 *  `FakeState` are EXTENSIONS (compiled to the `FakeComposablesKt` facade), so `val x by …{ fakeMutableStateOf() }`
 *  only compiles when they are imported — the missing-`getValue`-import case. */
operator fun <T> FakeState<T>.getValue(thisRef: Any?, property: KProperty<*>): T = value
operator fun <T> FakeState<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }

/** A `@Composable` top-level function compiled into the test classpath (facade `FakeComposablesKt`), so the
 *  binary `@Composable` detection can be exercised on real bytecode without the Compose compiler. */
@Composable
fun FakeText(text: String) {
}

/** A plain (non-composable) top-level function, to verify detection doesn't over-fire. */
fun plainHelper(x: Int): Int = x

/** A composable that takes a `@Composable` content lambda — like a layout's `content` slot. The annotation
 *  sits on the function TYPE of `content`, so the type-level `@Composable` detection can be exercised. */
@Composable
fun FakeColumn(content: @Composable () -> Unit) {
}

/** A NON-composable function whose lambda parameter is also non-composable — like `LazyListScope.items`'
 *  `key`/`contentType` slots — so the type-level detection doesn't over-fire on plain function-type params. */
fun fakeForEach(block: (Int) -> Unit) {
}

/** Like Compose's `Row`: a `@Composable FakeScope.() -> Unit` content slot. Inside it, `FakeScope`'s member
 *  extension (`FakeModifier.scopedPad`) is in scope — the `Row { Modifier.weight(1f) }` shape. */
@Composable
fun FakeRow(pad: Int = 0, content: @Composable FakeScope.() -> Unit) {
}

/** Mirrors Compose's `Box`/`Card`: a content-LESS overload (`Box(modifier)`) AND a content overload with the
 *  same leading defaulted param. `FakeBox { }` (a trailing lambda) must resolve to the CONTENT overload so its
 *  `@Composable FakeScope.() -> Unit` slot is seen — otherwise the content lambda reads as non-composable and a
 *  call inside it is falsely flagged (the reported `Card { Box { Column() } }` COMPOSABLE_INVOCATION false
 *  positive, which single-overload fakes never surfaced). */
@Composable
fun FakeBox(modifier: FakeModifier = FakeModifier) {}

@Composable
fun FakeBox(modifier: FakeModifier = FakeModifier, content: @Composable FakeScope.() -> Unit) {}

/** Like Compose's `remember`: `@Composable`, inline, returns the calculation's result type `T`. */
@Composable
inline fun <T> fakeRemember(calculation: () -> T): T = calculation()

/** Like Compose's `mutableStateOf`: single arg (NON-vararg), returns a `FakeState<T>`. */
fun <T> fakeMutableStateOf(value: T): FakeState<T> = FakeState(value)

/** Like Compose's `mutableStateListOf`: VARARG, returns a `FakeList<T>` (a `MutableList<T>`). The
 *  `remember { mutableStateListOf(...) }` → `todos.add(...)` shape that needs vararg-through-generic inference. */
fun <T> fakeMutableStateListOf(vararg elements: T): FakeList<T> = TODO()

/** Two overloads of the SAME composable that differ ONLY in a defaulted parameter the caller usually omits —
 *  the `androidx.compose.material3.SuggestionChip` shape (a current + a deprecated binary-compat overload).
 *  A `fakeChip(onClick = …, label = …)` call supplies neither distinguishing param, so the two are
 *  indistinguishable at that call site and must collapse to one rather than read as an ambiguity. */
@Composable
fun fakeChip(onClick: () -> Unit, label: @Composable () -> Unit, enabled: Boolean = true) {}

@Composable
fun fakeChip(onClick: () -> Unit, label: @Composable () -> Unit, tag: String = "") {}

/** Mirrors `androidx.compose.ui.text.input.TextFieldValue`: the alternative value type a text field's
 *  `value`/`onValueChange` pair can be typed over. */
class FakeTextFieldValue

/** Two overloads of the SAME composable that differ in the type of the leading `value` parameter AND its
 *  paired `onValueChange` callback (the `androidx.compose.material3.TextField` shape: a `String` overload and
 *  a `TextFieldValue` overload). A `fakeTextField(value = …, onValueChange = { … })` call disambiguates ONLY
 *  on the NAMED `value` argument's type, so the resolver must score named arguments to pick the right overload
 *  (and thereby type the lambda's `it`). The `TextFieldValue` overload is declared FIRST so a scorer that
 *  ignores named arguments and falls back to the first candidate picks the WRONG one (the regression). */
@Composable
fun fakeTextField(value: FakeTextFieldValue, onValueChange: (FakeTextFieldValue) -> Unit) {}

@Composable
fun fakeTextField(value: String, onValueChange: (String) -> Unit) {}

/** A top-level `suspend` function compiled into the test classpath (facade `FakeComposablesKt`), so the
 *  suspend calling-convention check can be exercised against a real BINARY `suspend` symbol. */
suspend fun fakeSuspendWork() {}

/** A NON-inline builder whose lambda is a `suspend () -> Unit` slot, the coroutine-builder shape
 *  (`launch`/`withContext`). The binary/`@Metadata` path decodes a suspend function type as a continuation-
 *  expanded plain `FunctionN` (the suspend marker is lost), so the resolver cannot prove this lambda is a
 *  suspend slot and must back off (no diagnostic) rather than false-flag a suspend call inside it. */
fun fakeCoroutineBuilder(block: suspend () -> Unit) {}

/** A per-item scope, like `LazyItemScope`. */
class FakeItemScope

/** A list-DSL scope, like `LazyListScope`. Its `fakeItem` is a scope MEMBER (not an extension) with a
 *  defaulted param + composable content — exactly the `LazyListScope.item` shape. A bare call to it inside
 *  `fakeLazyColumn { }` must dispatch on the implicit scope receiver (the `$default` synthetic takes the
 *  scope as its `$this`), NOT fall through to a receiver-less top-level call. */
interface FakeListScope {
    fun fakeItem(key: Any? = null, content: @Composable FakeItemScope.() -> Unit)
}

/** A content slot whose lambda receives a [FakeListScope], like `LazyColumn`'s. */
fun fakeLazyColumn(content: FakeListScope.() -> Unit) {}

/** Mirrors `androidx.compose.material.icons.Icons`: an object with NESTED objects, reached as
 *  `FakeIcons.AutoMirrored.Filled`. */
object FakeIcons {
    object AutoMirrored {
        object Filled
    }
}

/** Mirrors `androidx.compose.foundation.lazy.grid.GridCells`: an INTERFACE with nested CLASSES that carry
 *  constructors (`GridCells.Fixed(count)`, `GridCells.Adaptive(minSize)`). Kotlin `@Metadata` keeps a class's
 *  nested classes in a separate `nestedClasses` list, NOT among its members, so `FakeGridCells.` completion
 *  only offers `Fixed`/`Adaptive` when that list is decoded into the type shape (the reported gap). */
interface FakeGridCells {
    class Fixed(count: Int) : FakeGridCells
    class Adaptive(minSize: Int) : FakeGridCells
}

/** An icon as an EXTENSION property on the deepest nested object — the `Icons.AutoMirrored.Filled.List`
 *  shape. It compiles to a static `getFakeListIcon(FakeIcons$AutoMirrored$Filled)` on this file's
 *  `FakeComposablesKt` facade, NOT a member of `Filled`, so binding it requires the resolver to first infer
 *  the nested-object receiver's type. */
val FakeIcons.AutoMirrored.Filled.FakeListIcon: String get() = "list-icon"

/** Like `LazyListScope.itemsIndexed`: a bare-called EXTENSION on the list scope, whose `itemContent` is a
 *  RECEIVER lambda WITH value params (`FakeItemScope.(index: Int, item: T) -> Unit`). The runtime passes
 *  `[itemScope, index, item]`, so the source lambda's first explicit param must bind to the INDEX (arg 1), not
 *  the item-scope. Being an extension called bare inside `fakeLazyColumn { }`, resolving its lambda's receiver
 *  exercises the implicit-receiver call-resolution path (not just top-level functions). */
fun <T> FakeListScope.fakeItemsIndexed(items: List<T>, itemContent: @Composable FakeItemScope.(Int, T) -> Unit) {}

/** Two overloads of `fakeItems` that mirror `LazyListScope.items`: an Int-based overload (no type param) and
 *  a generic List-based overload, BOTH with 4 parameters. A call that supplies only 3 args (first positional +
 *  named key lambda + trailing content lambda) must pick the List overload when the first arg is a `List<T>`,
 *  not the Int overload — exercising the binary-extension disambiguation path. */
fun FakeListScope.fakeItems(
    count: Int,
    key: ((Int) -> Any)? = null,
    contentType: ((Int) -> Any)? = null,
    itemContent: @Composable FakeItemScope.(Int) -> Unit = {},
) {}

fun <T> FakeListScope.fakeItems(
    items: List<T>,
    key: ((T) -> Any)? = null,
    contentType: ((T) -> Any)? = null,
    itemContent: @Composable FakeItemScope.(T) -> Unit = {},
) {}
