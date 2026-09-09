package dev.ide.ui.editor.blocks

import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** [rankForSocket]'s type-aware re-ranking: matches float up, order otherwise stays the backend's. */
class SlotCompletionTest {

    private fun item(label: String, detail: String?, kind: UiCompletionKind = UiCompletionKind.Method) =
        UiCompletionItem(label, label, detail, kind = kind, sortPriority = 0)

    @Test
    fun booleanSocketLiftsBooleanReturns() {
        val items = listOf(
            item("toString", "String"),
            item("isEmpty", "boolean"),
            item("size", "int"),
            item("equals", "Boolean"),
        )
        val ranked = rankForSocket(items, "boolean")
        assertEquals(listOf("isEmpty", "equals", "toString", "size"), ranked.map { it.label })
    }

    @Test
    fun numberSocketLiftsNumericPrimitivesAndBoxes() {
        val items = listOf(
            item("name", "String"),
            item("count", "int"),
            item("total", "Double"),
            item("flag", "boolean"),
        )
        val ranked = rankForSocket(items, "number")
        assertEquals(listOf("count", "total", "name", "flag"), ranked.map { it.label })
    }

    @Test
    fun stringSocketCountsCharAsText() {
        // char/Character read as text in the shape language — they match a string socket
        val items = listOf(
            item("size", "int"),
            item("charAt", "char"),
            item("name", "String"),
            item("seq", "CharSequence"),
        )
        val ranked = rankForSocket(items, "string")
        assertEquals(listOf("charAt", "name", "seq", "size"), ranked.map { it.label })
    }

    @Test
    fun typeSocketRanksByItemKindNotDetail() {
        val items = listOf(
            item("compute", "List<String>", UiCompletionKind.Method),
            item("ArrayList", null, UiCompletionKind.Class),
            item("Runnable", null, UiCompletionKind.Interface),
            item("value", "int", UiCompletionKind.Field),
        )
        val ranked = rankForSocket(items, "type")
        assertEquals(listOf("ArrayList", "Runnable", "compute", "value"), ranked.map { it.label })
    }

    @Test
    fun genericsAreStrippedBeforeMatching() {
        // "Stream<Integer>" is not a number; a generics-free "Integer" is
        val items = listOf(
            item("stream", "Stream<Integer>"),
            item("boxed", "Integer"),
        )
        val ranked = rankForSocket(items, "number")
        assertEquals(listOf("boxed", "stream"), ranked.map { it.label })
    }

    @Test
    fun rankingIsStableWithinEachBucket() {
        val items = listOf(
            item("a", "String"),
            item("b", "boolean"),
            item("c", "String"),
            item("d", "boolean"),
        )
        val ranked = rankForSocket(items, "boolean")
        assertEquals(listOf("b", "d", "a", "c"), ranked.map { it.label }, "ties keep the backend's order")
    }

    @Test
    fun unknownObjectAndNullKindsLeaveTheListUntouched() {
        val items = listOf(item("z", "boolean"), item("a", "String"))
        assertSame(items, rankForSocket(items, null))
        assertSame(items, rankForSocket(items, "unknown"))
        assertSame(items, rankForSocket(items, "object"))
    }

    @Test
    fun missingDetailNeverMatchesATypedSocket() {
        val items = listOf(item("a", null), item("b", "boolean"))
        val ranked = rankForSocket(items, "boolean")
        assertEquals(listOf("b", "a"), ranked.map { it.label })
    }
}
