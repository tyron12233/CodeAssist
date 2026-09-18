package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the false "unresolved" on `it` inside a lambda passed to a GENERIC function — the dashboard's
 * `items(xs, key = { it.id }) { … }`. `it` types as the function's type parameter `T`; when inference can't
 * pin `T` to the element type, `it` is a bare type parameter whose members can't be enumerated, so flagging a
 * member access on it (`it.id`) is a false positive. `myItems`/`P` live on disk (in the symbol model) so the
 * generic function is decoded with a real (marked) type parameter — mirroring a library/another-file callee.
 */
class KotlinLazyDslDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun memberAccessOnAGenericLambdaParamIsNotFalselyUnresolved() {
        val diags = diagnose(
            "Screen.kt",
            """
            package demo
            fun screen() {
                val xs = listOf(P("a", 1f))
                myItems(xs, key = { it.id })
            }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("id") },
            "`it.id` (it = the generic element type) must not be flagged unresolved; got $diags",
        )
    }

    @Test
    fun filterOnAListParameterResolves() {
        // The dashboard's `projects.filter { it.name.contains(q) }` where `projects: List<Project>` is a param.
        val diags = diagnose(
            "Filter.kt",
            """
            package demo
            fun screen(projects: List<P>): Any =
                projects.filter { it.id.contains("x") }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && (it.message.contains("filter") || it.message.contains("id")) },
            "`projects.filter { it.id… }` on a List<P> param must resolve; got $diags",
        )
    }

    /**
     * Regression for `import icons.automirrored.filled.List` (a Compose icon whose simple name is "List")
     * shadowing `kotlin.collections.List` in type annotations. Kotlin's built-in types are intrinsic — always
     * in scope via the implicit kotlin star import — and must not be displaced by an explicit import that
     * happens to share the same simple name. Simulated here by importing `StickyScope` under the alias `List`.
     */
    @Test
    fun kotlinBuiltinListNotShadowedByExplicitImportWithSameName() {
        val diags = diagnose(
            "IconList.kt",
            """
            package demo
            import demo.StickyScope as List
            fun screen(items: List<P>): Any = items.filter { it.id.isNotEmpty() }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && (it.message.contains("filter") || it.message.contains("id")) },
            "`List<P>` must resolve to kotlin.collections.List even when another type is imported as 'List'; got $diags",
        )
    }

    @Test
    fun composableCallInsideAComposableReceiverLambdaIsNotFlagged() {
        // Mirrors `stickyHeader { … }`: the content param is `@Composable Scope.() -> Unit` (composable AND a
        // receiver). A composable call inside it must be seen as a composable context.
        val diags = diagnose(
            "Sticky.kt",
            """
            package demo
            import androidx.compose.runtime.Composable
            fun screen() {
                stickyHeader { Greeting() }
            }
            @Composable fun Greeting() {}
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.composableInvocation" },
            "a composable call inside a `@Composable Scope.() -> Unit` lambda must not be flagged; got $diags",
        )
    }

    /**
     * Regression for the `items(list, key = { it.id }) { }` false-positive: two overloads with the same
     * parameter count (Int-based and List-based), where the call supplies fewer args than the param count
     * (defaulted params omitted). The resolver must pick the overload whose FIRST parameter matches the
     * first positional arg's type (`List<P>` → List overload), not fall through to `firstOrNull()` and
     * nondeterministically pick the Int overload, which would type `it` as `Int` and flag `.id` unresolved.
     * Source-function variant (top-level, found via topLevelByName).
     */
    @Test
    fun multiOverloadItemsWithDefaultedParamsPicksListOverloadByFirstArgType() {
        val diags = diagnose(
            "MultiItems.kt",
            """
            package demo
            import androidx.compose.runtime.Composable
            @Composable fun screen(projects: List<P>) {
                myMultiItems(projects, key = { it.id }) { project ->
                    val n = project.progress
                }
            }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("id") },
            "`it.id` must resolve when the List overload is picked (not the Int overload); got $diags",
        )
    }

    /**
     * Same disambiguation but via BINARY EXTENSIONS on an implicit receiver — the exact `LazyListScope.items`
     * path. `fakeItems` is compiled into the test classpath (FakeComposables.kt) as two 4-param overloads on
     * `FakeListScope`: Int-based and generic List-based. The call inside `fakeLazyColumn { }` supplies only 3
     * args (list.filter{} + named key lambda + trailing lambda); the first positional arg is `List<P>`, so the
     * List overload must be selected and `it.id` must resolve without an "unresolved reference" diagnostic.
     */
    @Test
    fun binaryExtensionItemsOnImplicitReceiverPicksListOverloadByFirstArgType() {
        val diags = diagnose(
            "BinaryItems.kt",
            """
            package demo
            import androidx.compose.runtime.Composable
            import dev.ide.fakecompose.fakeLazyColumn
            @Composable fun screen(projects: List<P>, query: String) {
                fakeLazyColumn {
                    fakeItems(projects.filter { it.id.contains(query) }, key = { it.id }) { project ->
                        val n = project.progress
                    }
                }
            }
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && it.message.contains("id") },
            "`it.id` via binary-extension fakeItems on implicit FakeListScope receiver must not flag unresolved; got $diags",
        )
    }

    /**
     * Regression for `stickyHeader { Box(modifier) { Row(…) } }`: when `Box` has defaulted params and the
     * call supplies fewer args than param count, the resolver must still pick the overload with a composable
     * content lambda (not a no-content overload), so the composable context walk sees `isComposable = true`
     * on the trailing lambda's expected type and does NOT flag the inner `Row` invocation.
     */
    @Test
    fun composableCallInsideNestedDefaultedParamComposableLambdaIsNotFlagged() {
        val diags = diagnose(
            "Nested.kt",
            """
            package demo
            import androidx.compose.runtime.Composable
            @Composable fun screen() {
                stickyHeader {
                    myBox {
                        Greeting()
                    }
                }
            }
            @Composable fun Greeting() {}
            """.trimIndent(),
        )
        assertTrue(
            diags.none { it.code == "kt.composableInvocation" },
            "a composable call inside `stickyHeader { myBox { … } }` must not be flagged; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Models.kt" to "package demo\ndata class P(val id: String, val progress: Float)\n",
                "Dsl.kt" to "package demo\n" +
                    "import androidx.compose.runtime.Composable\n" +
                    "fun <T> myItems(items: List<T>, key: (T) -> Any) {}\n" +
                    "class StickyScope\n" +
                    "fun stickyHeader(content: @Composable StickyScope.() -> Unit) {}\n" +
                    // Two overloads that both have 4 params (same as real LazyListScope.items):
                    // one Int-based (no type param), one List-based (generic). The call supplies
                    // only 2 positional-or-named args + 1 trailing lambda (3 total, < 4 params).
                    // Disambiguation must pick the List overload when the first arg is a List<T>.
                    "fun myMultiItems(count: Int, key: ((Int) -> Any)? = null, contentType: ((Int) -> Any)? = null, itemContent: (Int) -> Unit = {}) {}\n" +
                    "fun <T> myMultiItems(items: List<T>, key: ((T) -> Any)? = null, contentType: ((T) -> Any)? = null, itemContent: (T) -> Unit = {}) {}\n" +
                    // A composable with 4 params, 3 of which are defaulted — mirrors Compose's Box.
                    // A call with only a trailing lambda (1 arg < 4 params) must still resolve to
                    // this overload so the content lambda's composable context is detected.
                    "@Composable fun myBox(modifier: Int = 0, alignment: Int = 0, propagate: Boolean = false, content: @Composable () -> Unit) {}\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
