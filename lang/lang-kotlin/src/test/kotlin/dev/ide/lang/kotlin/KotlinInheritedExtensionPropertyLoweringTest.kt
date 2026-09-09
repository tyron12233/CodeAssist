package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
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
}
