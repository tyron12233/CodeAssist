package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A bare EXTENSION property on the enclosing class's implicit `this` must resolve when lowering for the
 * Compose preview — not fall through to "unresolved name". The reported case: `viewModelScope` (a
 * `val ViewModel.viewModelScope` extension) read inside a `class VM : ViewModel()` body. Before the fix
 * `nameNode` only saw the class's OWN declared properties, so an inherited/extension property blanked the
 * whole class ("Preview not interpretable: unresolved name `viewModelScope`").
 */
class KotlinInheritedExtensionPropertyLoweringTest {

    @Test
    fun bareExtensionPropertyOnThisResolves() {
        val code =
            "package demo\n" +
                "open class ViewModel\n" +
                "val ViewModel.viewModelScope: Int get() = 0\n" +
                "class VM : ViewModel() {\n" +
                "  val scope = viewModelScope\n" +
                "}\n"
        val srcDir = tempProject(mapOf("VM.kt" to code))
        val service = KotlinSymbolService(listOf(DiskFile(srcDir)), listOf(stdlibJarPath()))
        val parsed = KotlinParserHost.parse("VM.kt", code).let { KotlinParsedFile(it, DiskFile(srcDir.resolve("VM.kt")), 0) }
        val vm = KotlinPreviewLowering(service).classes(parsed).firstOrNull { it.fqn == "demo.VM" }
        assertTrue(
            vm != null && vm.isComplete,
            "a bare extension property on the enclosing `this` must resolve, not blank the class; diags=${vm?.diagnostics?.map { it.reason }}",
        )
    }

    @Test
    fun extensionPropertyGetterSeesItsReceiverAsImplicitThis() {
        // JetNews PostContent.kt: `private val ColorScheme.codeBlockBackground: Color get() = onSurface.copy(alpha = .15f)`.
        // The bare `onSurface` is a member of the extension RECEIVER; the implicit-receiver walk covered extension
        // functions only, so the editor flagged "Unresolved reference: onSurface" and the preview refused the file.
        val code = "package demo\n" +
            "class Paint(val a: Int) { fun copy(alpha: Int): Paint = Paint(alpha) }\n" +
            "class Scheme(val onSurface: Paint)\n" +
            "val Scheme.codeBlockBackground: Paint\n" +
            "    get() = onSurface.copy(alpha = 15)\n" +
            "fun use(): Int = Scheme(Paint(1)).codeBlockBackground.a\n"
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath())))
        val codes = runBlocking {
            val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file).diagnostics.map { "${'$'}{it.code}:${'$'}{it.message}" }
        }
        assertFalse(codes.any { it.startsWith("kt.unresolved") }, "the receiver's member must resolve inside the getter; diagnostics=$codes")

        val srcDir2 = tempProject(mapOf("D.kt" to code))
        val service = KotlinSymbolService(listOf(DiskFile(srcDir2)), listOf(stdlibJarPath()))
        val parsed = KotlinParserHost.parse("D.kt", code).let { KotlinParsedFile(it, DiskFile(srcDir2.resolve("D.kt")), 0) }
        val model = KotlinPreviewLowering(service).crossFileModel(parsed)
        val getter = model.program["codeBlockBackground/0"]
        assertTrue(
            getter != null && getter.isComplete,
            "the extension property getter must lower completely (its `copy` call resolves on the receiver member); diags=${getter?.diagnostics?.map { it.reason }}",
        )
    }
}
