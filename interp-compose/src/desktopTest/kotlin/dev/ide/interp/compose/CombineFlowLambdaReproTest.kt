package dev.ide.interp.compose

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinIncrementalParser
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `combine(flowA, flowB) { a, key -> … }` over REAL `kotlinx.coroutines.flow` on the test classpath must
 * infer each lambda parameter from the corresponding flow's element type — so `key` is `TextFieldValue`
 * (`flowB: MutableStateFlow<TextFieldValue>`) and `key.text.isBlank()` resolves. The regression: the flow was
 * built by the `MutableStateFlow(value)` FACTORY function, whose result the resolver mis-read as a construction
 * of the `MutableStateFlow` INTERFACE (which has no constructor) instead of the factory's inferred return type —
 * leaving the element a bare type parameter `T`, so `key.text` was unresolved and the preview reported
 * "unresolved/ambiguous call `isBlank` (candidates=0)" and refused to render.
 */
class CombineFlowLambdaReproTest {

    private fun classpathJars(): List<Path> =
        System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }.map { Paths.get(it) }

    @Test
    fun combineLambdaParamsInferFactoryFlowElementTypes() {
        val code = """
            package demo
            import androidx.compose.ui.text.input.TextFieldValue
            import kotlinx.coroutines.flow.MutableStateFlow
            import kotlinx.coroutines.flow.combine

            class OrderViewModel {
                private val _allFoodList = MutableStateFlow<MutableList<TextFieldValue>>(mutableListOf())
                private val _searchKeyword = MutableStateFlow(TextFieldValue(""))

                val filtered = combine(_allFoodList, _searchKeyword) { allList, key ->
                    if (key.text.isBlank()) {
                        allList
                    } else {
                        allList.filter { item -> item.text.contains(key.text, ignoreCase = true) }
                    }
                }
            }
        """.trimIndent()
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = classpathJars())
        val parsed = KotlinIncrementalParser().parseFull(Doc(code)) as KotlinParsedFile
        val vm = KotlinPreviewLowering(service).classes(parsed).firstOrNull { it.fqn == "demo.OrderViewModel" }
        val diagnostics = vm?.diagnostics.orEmpty().map { it.reason }
        assertTrue(
            vm != null && vm.isComplete,
            "the OrderViewModel lowering must be complete (the `combine` lambda's `key` infers TextFieldValue so " +
                "`key.text.isBlank()` resolves); diags=$diagnostics",
        )
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
