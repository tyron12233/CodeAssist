package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A sealed interface's nested subtype constructed from ANOTHER file — the usual layout (the model in one file,
 * the `@Preview` in another) — must lower to a SOURCE constructor, not a reflective LIBRARY one. The type isn't
 * compiled at preview time, so a reflective `Class.forName("…ListItemUiModel$Title")` throws "cannot load class".
 * The same-file `fileClasses` fast path misses cross-file; the whole-project source registry
 * (`service.isSourceClass`) must catch it and pick the source callee so the cross-file merge builds a SourceObject.
 */
class SealedSubtypePreviewLoweringTest {

    private val models = """
        package demo
        sealed interface ListItemUiModel {
            data class Title(val text: String) : ListItemUiModel
            data class Row(val n: Int) : ListItemUiModel
        }
    """.trimIndent()

    private fun lowerPreview(previewCode: String): ResolvedFunction {
        val dir = tempProject(mapOf("Models.kt" to models, "Preview.kt" to previewCode))
        val service = KotlinSymbolService(sourceRoots = listOf(DiskFile(dir)), classpathJars = emptyList())
        val kt = KotlinParserHost.parse("Preview.kt", previewCode)
        val parsed = KotlinParsedFile(kt, DiskFile(dir.resolve("Preview.kt")), 0)
        return assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
    }

    private fun titleCall(fn: ResolvedFunction): RNode.Call {
        var ctor: RNode.Call? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == "Title") ctor = it }
        return assertNotNull(ctor, "the `Title(...)` call must lower to a Call; diags=${fn.diagnostics}")
    }

    @Test
    fun qualifiedCrossFileSealedSubtypeLowersToSourceConstructor() {
        val fn = lowerPreview("package demo\nfun p() { val x = ListItemUiModel.Title(\"hi\") }\n")
        val call = titleCall(fn)
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "must be a CONSTRUCTOR, was ${call.dispatch}")
        assertTrue(
            call.callee is ResolvedCallable.Source,
            "a project-source sealed subtype must lower to a SOURCE ctor (not a reflective Library that fails " +
                "with `cannot load class ListItemUiModel\$Title`), was ${call.callee::class.simpleName}",
        )
    }

    @Test
    fun bareImportedCrossFileSealedSubtypeLowersToSourceConstructor() {
        val fn = lowerPreview("package demo\nimport demo.ListItemUiModel.Title\nfun p() { val x = Title(\"hi\") }\n")
        val call = titleCall(fn)
        assertTrue(call.dispatch == DispatchKind.CONSTRUCTOR, "must be a CONSTRUCTOR, was ${call.dispatch}")
        assertTrue(
            call.callee is ResolvedCallable.Source,
            "a bare imported source subtype must lower to a SOURCE ctor, was ${call.callee::class.simpleName}",
        )
    }
}
