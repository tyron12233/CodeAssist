package dev.ide.android.support

import dev.ide.android.support.metadata.SourceCustomViewResolver
import dev.ide.index.IndexId
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceCustomViewResolverTest {

    /** A fake direct-inheritor index: (id, key) -> subtypes. */
    private fun index(vararg entries: Pair<Pair<IndexId, String>, List<SubtypeValue>>):
        (IndexId, String) -> Sequence<SubtypeValue> {
        val map = entries.toMap()
        return { id, key -> (map[id to key] ?: emptyList()).asSequence() }
    }

    @Test
    fun findsSourceViewSubclassesTransitivelyWithAncestry() {
        val query = index(
            (SubtypeIndex.JAVA_SOURCE to "Button") to listOf(
                SubtypeValue("com.example.MyButton", "class", "android.widget.Button"),
            ),
            (SubtypeIndex.JAVA_SOURCE to "MyButton") to listOf(
                SubtypeValue("com.example.FancyButton", "class", "com.example.MyButton"),
            ),
        )
        val scan = SourceCustomViewResolver.resolve(mapOf("Button" to false, "View" to false), query)

        assertEquals(
            setOf("com.example.MyButton", "com.example.FancyButton"),
            scan.widgets.map { it.tag }.toSet(),
        )
        // Ancestry bridges into the framework name so attribute inheritance can walk MyButton -> Button.
        assertEquals("Button", scan.superNames["MyButton"])
        assertEquals("MyButton", scan.superNames["FancyButton"])
    }

    @Test
    fun ignoresNonAndroidSupertypeCollisionAtSeed() {
        // A source class extends some unrelated `Button` (not android.widget.Button) — must NOT be treated as a view.
        val query = index(
            (SubtypeIndex.JAVA_SOURCE to "Button") to listOf(
                SubtypeValue("com.other.Widget", "class", "com.other.Button"),
            ),
        )
        val scan = SourceCustomViewResolver.resolve(mapOf("Button" to false), query)
        assertTrue(scan.widgets.isEmpty(), "non-android supertype must not be pulled in; got ${scan.widgets}")
    }

    @Test
    fun isViewGroupPropagatesFromFrameworkBase() {
        val query = index(
            (SubtypeIndex.JAVA_SOURCE to "LinearLayout") to listOf(
                SubtypeValue("com.example.MyRow", "class", "android.widget.LinearLayout"),
            ),
        )
        val scan = SourceCustomViewResolver.resolve(mapOf("LinearLayout" to true), query)
        assertEquals(true, scan.widgets.single { it.tag == "com.example.MyRow" }.isViewGroup)
    }
}
