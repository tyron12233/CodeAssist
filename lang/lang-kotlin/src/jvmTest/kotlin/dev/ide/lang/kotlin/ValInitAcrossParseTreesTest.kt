package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.SourceIndexBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `val` declared without a value and assigned in `init` is its initialization, even when the symbol model holds
 * the declaration from ANOTHER parse of the file than the one being checked (the model is re-synced per pass,
 * keyed by text, so it can sit one keystroke ahead or behind). Field report: every deferred `val` of a custom
 * `LinearLayout` (`vertical = ScrollView(context).apply { … }` in `init`) read "Val cannot be reassigned".
 */
class ValInitAcrossParseTreesTest {

    private val file = FakeFile("Main.kt")

    /** The `kt.valReassign` targets in [checked], with the model synced to a parse of [modelText]. */
    private fun valReassigns(checked: String, modelText: String): List<String> {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath().toString()))
        val model = KotlinParsedFile(KotlinParserHost.parse(file.name, modelText), file, 2)
        service.syncFocal(file.path, modelText.hashCode()) { SourceIndexBuilder.extractFrom(model.ktFile, model, file.path) }
        // The same text is checked on the very tree the model was built from (the normal, in-step case).
        val tree = if (checked == modelText) model else KotlinParsedFile(KotlinParserHost.parse(file.name, checked), file, 1)
        return IncrementalSemanticAnalysis(service).diagnostics(tree)
            .filter { it.code == KotlinDiagnosticCodes.VAL_REASSIGN }
            .map { checked.substring(it.range.start, it.range.end) }
    }

    private val viewer = """
        package demo
        open class Base { var orientation = 0 }
        class Viewer : Base() {
            private val vertical: String
            private val row: String
            init {
                orientation = 1
                vertical = "a"
                row = "b"
            }
            fun reset() { row = "c" }
        }
    """.trimIndent()

    @Test
    fun initAssignmentsAreInitializationWhenTheModelIsAnotherParse() {
        // The model is the next keystroke's tree (a trailing comment typed); the checks walk this one. Only the
        // assignment from a member function is a reassignment.
        assertEquals(listOf("row"), valReassigns(viewer, viewer + "\n// typing"))
    }

    @Test
    fun theInStepCaseAgrees() {
        assertEquals(listOf("row"), valReassigns(viewer, viewer))
    }

    @Test
    fun anotherClassOfTheSameShapeStillCannotInitializeIt() {
        // A nested class's init assigning the outer's deferred val is not the outer's initialization.
        val code = """
            package demo
            class Outer {
                private val v: String
                init { v = "a" }
                inner class Inner { init { v = "b" } }
            }
        """.trimIndent()
        assertEquals(listOf("v"), valReassigns(code, code + "\n// typing"))
    }
}
