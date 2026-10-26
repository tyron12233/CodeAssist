package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.completion.KotlinPostfixTemplates
import dev.ide.lang.postfix.PostfixContext
import dev.ide.lang.resolve.Symbol
import dev.ide.lang.resolve.TypeRef
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Kotlin live templates appear only where they fit (`sout` in a body, `comp` among declarations), the Android
 * and Compose ones only when their library is on the classpath, and the ones that read their surroundings
 * fill in the class, function and parameters. The libraries are stand-in source classes here.
 */
class KotlinLiveTemplatesTest {

    private fun templates(code: String, project: Path = android): List<CompletionItem> =
        runBlocking { KotlinSourceAnalyzer(fakeContext(project)).completeAtCaret(project, "U.kt", code) }
            .items.filter { it.kind == CompletionItemKind.SNIPPET }

    private fun labels(code: String, project: Path = android) = templates(code, project).map { it.label }.toSet()

    private fun item(key: String, code: String) =
        assertNotNull(templates(code).firstOrNull { it.label == key }, "no $key in ${labels(code)}")

    @Test
    fun eachTemplateOnlyWhereItFits() {
        assertTrue("sout" in labels("package demo\nfun f() {\n    sou|\n}"))
        assertTrue("sout" !in labels("package demo\nsou|"), "no statement at the top level")
        assertTrue("comp" in labels("package demo\ncom|"))
        assertTrue("comp" !in labels("package demo\nfun f() {\n    com|\n}"), "no declaration template inside a body")
        assertTrue("prev" in labels("package demo\npre|"))
        assertTrue("exfun" in labels("package demo\nexf|"))
        assertTrue("closure" in labels("package demo\nfun f() {\n    val g = clo|\n}"))
    }

    @Test
    fun libraryTemplatesNeedTheirLibrary() {
        val inBody = "package demo\nfun f() {\n    lo|\n}"
        assertTrue(labels(inBody, plain).none { it.startsWith("log") }, "no Log templates without android.util.Log")
        assertTrue(labels("package demo\nco|", plain).none { it == "comp" }, "no comp without Compose")
        assertEquals(setOf("logd", "logi", "logw", "loge", "logm"), labels(inBody).filter { it.startsWith("log") }.toSet())
    }

    @Test
    fun templatesReadTheEnclosingClassAndFunction() {
        val body = "package demo\nclass MainActivity {\n    fun onCreate(state: Int, flags: Int) {\n        KEY|\n    }\n}"
        assertEquals("Log.d(TAG, \"onCreate: \")", item("logd", body.replace("KEY", "logd")).insertText)
        assertEquals(
            "Log.d(TAG, \"onCreate() called with: state = \$state, flags = \$flags\")",
            item("logm", body.replace("KEY", "logm")).insertText,
        )
        assertEquals("private val TAG = \"MainActivity\"", item("logt", "package demo\nclass MainActivity {\n    logt|\n}").insertText)
        assertEquals("private const val TAG = \"Screen\"", item("logt", "package demo\nlogt|\nclass Screen").insertText)
        assertEquals(
            "private const val TAG = \"Holder\"",
            item("logt", "package demo\nobject Holder {\n    logt|\n}").insertText,
            "an object allows a const",
        )
    }

    @Test
    fun libraryTemplatesImportWhatTheyUse() {
        val logd = item("logd", "package demo\n\nfun f() {\n    logd|\n}")
        assertEquals(listOf("\n\nimport android.util.Log"), logd.additionalEdits.map { it.newText })
        val prev = item("prev", "package demo\n\nimport androidx.compose.runtime.Composable\n\nprev|")
        assertEquals(listOf("\nimport androidx.compose.ui.tooling.preview.Preview"), prev.additionalEdits.map { it.newText })
    }

    @Test
    fun aMultiLineTemplateTakesOnTheLinesIndent() {
        val `if` = templates("package demo\nclass A {\n    fun f() {\n        if|\n    }\n}").first { it.label == "if" }
        assertEquals("if (cond) {\n            \n        }", `if`.insertText)
    }

    @Test
    fun modifierTemplatesNeedAModifier() {
        val paddp = KotlinPostfixTemplates.all().first { it.key == "paddp" }
        val modifier = postfix("Modifier", "androidx.compose.ui.Modifier.Companion")
        assertTrue(paddp.isApplicable(modifier))
        assertTrue(!paddp.isApplicable(postfix("x", "kotlin.Int")), "padding is a Modifier's")
        val expansion = paddp.expand(modifier)
        assertEquals("Modifier.padding(16.dp)", expansion.snippet!!.text)
        assertEquals(
            listOf("import androidx.compose.foundation.layout.padding", "import androidx.compose.ui.unit.dp"),
            expansion.edits.map { it.newText.trim() },
        )
    }

    private fun postfix(receiver: String, typeFqn: String) = PostfixContext(
        Node, receiver, TextRange(0, receiver.length),
        object : TypeRef {
            override val qualifiedName = typeFqn
            override val typeArguments = emptyList<TypeRef>()
            override fun isAssignableFrom(other: TypeRef) = false
            override fun supertypes() = emptyList<TypeRef>()
            override fun members(accessibleFrom: Symbol?) = emptyList<Symbol>()
        },
        Doc("package demo\n\nfun f() {\n    $receiver.\n}"),
    )

    private object Node : DomNode {
        override val kind = NodeKind.NAME_REF
        override val range = TextRange(0, 0)
        override val parent: DomNode? = null
        override val children = emptyList<DomNode>()
        override fun text(): CharSequence = ""
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = DiskFile(Path.of("/f.kt"))
        override val version = 1L
        override fun length() = text.length
    }

    companion object {
        val plain: Path = tempProject(mapOf("Local.kt" to "package demo\n"))
        val android: Path = tempProject(
            mapOf(
                "Local.kt" to "package demo\n",
                "Log.kt" to "package android.util\nobject Log { fun d(tag: String, msg: String) = 0 }\n",
                "Toast.kt" to "package android.widget\nclass Toast\n",
                "Composable.kt" to "package androidx.compose.runtime\nannotation class Composable\n",
                "Preview.kt" to "package androidx.compose.ui.tooling.preview\nannotation class Preview\n",
            ),
        )
    }
}
