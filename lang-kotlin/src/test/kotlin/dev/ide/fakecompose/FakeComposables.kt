package dev.ide.fakecompose

import androidx.compose.runtime.Composable

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
