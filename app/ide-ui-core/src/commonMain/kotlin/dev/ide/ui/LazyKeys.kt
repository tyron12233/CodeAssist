package dev.ide.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * `items` / `itemsIndexed` for a `LazyColumn` or `LazyRow` whose keys **cannot** collide.
 *
 * A repeated key in a lazy list is fatal: `LayoutNodeSubcompositionsState.subcompose` throws
 * `IllegalArgumentException("Key \"…\" was already used…")` during the measure pass and the whole screen
 * goes down. The throw happens before any item content composes, so no `dev.ide.*` frame reaches the
 * stack and the analytics row cannot name the list — see docs/analytics.md. That is why this is a
 * structural guard rather than another audit: three rounds of auditing `key =` lambdas (the tab strip,
 * the dependency lists, the store feed, the leaderboards, the project picker) each fixed the producers
 * we could think of, and the crash kept arriving from the next list whose data the app does not control
 * (git refs, search hits, catalogue rows, resource names, module names).
 *
 * [uniqueKeys] keeps every key a caller supplies and only renames the *repeats*, so a list with distinct
 * keys behaves exactly as `items(…, key = …)` does today: identity is preserved across recompositions,
 * `animateItem` still works, and saved item state still restores. A repeat gets a NUL-joined ordinal
 * appended, which is checked against the keys already taken, so the renaming itself cannot collide.
 *
 * Use these in place of `items(list, key = …)` / `itemsIndexed(list, key = …)` everywhere. A list with no
 * key at all is already safe (Compose keys it by index) and needs no change.
 */
fun <T> LazyListScope.itemsKeyed(
    items: List<T>,
    key: (T) -> Any,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val keys = uniqueKeys(items, key)
    items(
        count = items.size,
        key = { index -> keys[index] },
        contentType = { index -> contentType(items[index]) },
    ) { index -> itemContent(items[index]) }
}

/** [itemsKeyed] for a list whose key and content both need the index. */
fun <T> LazyListScope.itemsIndexedKeyed(
    items: List<T>,
    key: (Int, T) -> Any,
    contentType: (Int, T) -> Any? = { _, _ -> null },
    itemContent: @Composable LazyItemScope.(Int, T) -> Unit,
) {
    val keys = uniqueKeysIndexed(items, key)
    items(
        count = items.size,
        key = { index -> keys[index] },
        contentType = { index -> contentType(index, items[index]) },
    ) { index -> itemContent(index, items[index]) }
}

/**
 * The keys [items] should be drawn with: [key] applied to each element, with any repeat renamed.
 *
 * The first element to claim a key keeps it. A later element whose key is already taken gets
 * `"<key>\u0000<n>"` for the lowest `n` that is itself free, so the result is distinct by construction
 * even if a caller's own keys happen to contain the separator. The renamed key is derived only from the
 * natural key and the number of earlier repeats, so it is stable for the same list content and an item
 * does not lose its identity (or its animation) between recompositions.
 *
 * Separate from [itemsKeyed] so the invariant can be tested without a composition.
 */
fun <T> uniqueKeys(items: List<T>, key: (T) -> Any): List<Any> =
    uniqueKeysIndexed(items) { _, item -> key(item) }

/** [uniqueKeys] for a key that also reads the element's index. */
fun <T> uniqueKeysIndexed(items: List<T>, key: (Int, T) -> Any): List<Any> {
    val taken = HashSet<Any>(items.size * 2)
    val out = ArrayList<Any>(items.size)
    for ((index, item) in items.withIndex()) {
        val natural = key(index, item)
        if (taken.add(natural)) {
            out.add(natural)
            continue
        }
        var n = 1
        var renamed: Any = "$natural\u0000$n"
        while (!taken.add(renamed)) {
            n++
            renamed = "$natural\u0000$n"
        }
        out.add(renamed)
    }
    return out
}
