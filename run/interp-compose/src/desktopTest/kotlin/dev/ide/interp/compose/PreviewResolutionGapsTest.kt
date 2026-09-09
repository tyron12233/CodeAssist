package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three shapes of ordinary Kotlin the preview lowering used to leave unresolved, reported from JetNews after
 * 3.14: `in interceptKey: unresolved name \`KeyUp\``, `in LoadingContent: unresolved/ambiguous call
 * \`Indicator\``, `in InterestScreenContent: unresolved/ambiguous call \`content\``, and
 * `unresolved/ambiguous call \`roundToPx\` (candidates=11, recv=…Dp)`.
 *
 * All three predate 3.14 — what 3.14 changed is that a reachable function which doesn't lower cleanly now
 * REFUSES the preview instead of having its statement silently skipped mid-composition, so gaps that used to
 * cost one dropped statement started blanking whole screens. They are lowering gaps either way, and each one
 * is a construct the interpreter can run perfectly well once resolution hands it over.
 *
 * Lowered against the real Compose stack on the desktop test classpath, so a candidate set that only exists
 * on a real Compose classpath (`roundToPx`'s eleven) is the one being resolved.
 */
class PreviewResolutionGapsTest {

    /**
     * A `fun interface` whose abstract method is a member EXTENSION gives the lambda converted to it that
     * receiver as its implicit `this`. `Layout`'s `MeasurePolicy` is `MeasureScope.measure(…)`, so the measure
     * lambda's body has a `MeasureScope` — which is a `Density`, and that is what selects `Dp.roundToPx()` out
     * of the eleven `roundToPx` candidates a Compose classpath carries, and what makes the bare `layout(…)`
     * (a `MeasureScope` MEMBER) resolve at all. The receiver was dropped because a SAM's shape was built with
     * `isExtension = false`, so nothing was in scope: `layout` came back `candidates=0` and `roundToPx`
     * ambiguous. JetNews's `InterestsAdaptiveContentLayout` is exactly this, four `roundToPx` calls deep.
     */
    @Test
    fun aFunInterfaceLambdaGetsItsAbstractMethodsExtensionReceiver() {
        val code = """
            package demo

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.layout.Layout
            import androidx.compose.ui.unit.Dp
            import androidx.compose.ui.unit.dp

            @Composable
            fun AdaptiveLayout(
                modifier: Modifier = Modifier,
                topPadding: Dp = 0.dp,
                itemSpacing: Dp = 4.dp,
                content: @Composable () -> Unit,
            ) {
                Layout(modifier = modifier, content = content) { measurables, outerConstraints ->
                    val topPaddingPx = topPadding.roundToPx()
                    val itemSpacingPx = itemSpacing.roundToPx()
                    layout(0, 0) {}
                }
            }
        """.trimIndent()
        assertLowersClean(code, "AdaptiveLayout/4")
    }

    /**
     * A member of an `object` or a companion object needs no dispatch receiver at the call site, which is why
     * Kotlin lets it be IMPORTED and then used by its simple name — `import …CardDefaults.cardColors` (the
     * `PullToRefreshDefaults.Indicator` shape JetNews's `LoadingContent` uses) and
     * `import …KeyEventType.Companion.KeyUp` (its `KeyEvents.kt`). Only the EXTENSION half of that rule was
     * modeled, so a plain member stayed out of the bare-name scope entirely; the qualified spellings
     * (`CardDefaults.cardColors()`, `KeyEventType.KeyUp`) always worked. The lowering has to put back the
     * receiver the source omits, since the JVM member is an instance one on the singleton.
     */
    @Test
    fun anImportBringsAnObjectOrCompanionMemberIntoScopeByItsSimpleName() {
        assertLowersClean(
            """
            package demo

            import androidx.compose.material3.Card
            import androidx.compose.material3.CardDefaults.cardColors
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable

            @Composable
            fun Boxed() {
                Card(colors = cardColors()) { Text("hi") }
            }
            """.trimIndent(),
            "Boxed/0",
        )
        assertLowersClean(
            """
            package demo

            import androidx.compose.ui.Modifier
            import androidx.compose.ui.input.key.Key
            import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
            import androidx.compose.ui.input.key.KeyEventType.Companion.KeyUp
            import androidx.compose.ui.input.key.key
            import androidx.compose.ui.input.key.onPreviewKeyEvent
            import androidx.compose.ui.input.key.type

            fun Modifier.interceptKey(key: Key, onKeyEvent: () -> Unit): Modifier =
                this.onPreviewKeyEvent {
                    if (it.key == key && it.type == KeyUp) {
                        onKeyEvent()
                        true
                    } else {
                        it.type == KeyDown
                    }
                }
            """.trimIndent(),
            "interceptKey/2",
        )
        // The same rule for a companion's PROPERTY read bare, not just a function called bare.
        assertLowersClean(
            """
            package demo

            import androidx.compose.ui.unit.Dp
            import androidx.compose.ui.unit.Dp.Companion.Unspecified

            fun unset(): Dp = Unspecified
            """.trimIndent(),
            "unset/0",
        )
    }

    /**
     * Kotlin's invoke convention on a property of function type: `tab.content()` reads `content` and invokes
     * the value. A local or a parameter of function type already lowered this way; a class's PROPERTY did not,
     * so a holder of composable slots — JetNews's `TabContent(section, content)`, called as
     * `tabContent.content()` — came back `candidates=0, recv=TabContent`, which is the whole tab body.
     */
    @Test
    fun aFunctionTypedPropertyIsInvokedThroughTheInvokeConvention() {
        assertLowersClean(
            """
            package demo

            import androidx.compose.foundation.layout.Column
            import androidx.compose.runtime.Composable

            class TabContent(val section: String, val content: @Composable () -> Unit)

            @Composable
            fun Screen(tab: TabContent) {
                Column {
                    tab.content()
                }
            }
            """.trimIndent(),
            "Screen/1",
        )
        // A plain (non-composable) function-typed property, and the explicit `.invoke()` spelling of it.
        assertLowersClean(
            """
            package demo

            class Handlers(val onClick: () -> Unit)

            fun fire(h: Handlers) {
                h.onClick()
                h.onClick.invoke()
            }
            """.trimIndent(),
            "fire/1",
        )
    }

    /** Lower [code] and assert the entry keyed [key] carries no lowering diagnostic. */
    private fun assertLowersClean(code: String, key: String) {
        val service = previewSymbolService()
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val program = KotlinPreviewLowering(service).program(parsed)
        val entry = assertNotNull(program[key], "`$key` must lower; lowered: ${program.keys}")
        val diagnostics = entry.diagnostics.map { it.reason }
        assertTrue(diagnostics.isEmpty(), "`$key` must lower clean; got $diagnostics")
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = F()
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private class F : VirtualFile {
        override val path = "Main.kt"; override val name = "Main.kt"; override val isDirectory = false
        override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
