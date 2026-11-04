package dev.ide.lsp.client

import dev.ide.analysis.AnalysisTarget
import dev.ide.index.IndexService
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.BasicCompletionResultSet
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lsp.LanguageServerConnection
import dev.ide.lsp.LanguageServerContext
import dev.ide.lsp.LanguageServerDefinition
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client against a real server: clangd, wherever one is installed (macOS ships one with Xcode; set
 * `CLANGD` to point at another). Skipped when there is none, so CI without it stays green.
 */
class ClangdIntegrationTest {
    private lateinit var root: Path
    private lateinit var manager: LspSessionManager
    private lateinit var support: LspLanguageSupport

    private val clangd: String? = (System.getenv("CLANGD")?.let(::listOf) ?: listOf("/usr/bin/clangd", "/usr/local/bin/clangd", "/opt/homebrew/opt/llvm/bin/clangd"))
        .firstOrNull { Files.isExecutable(Paths.get(it)) }

    private val definition = object : LanguageServerDefinition {
        override val id = "clangd"
        override val languages = setOf(LanguageId("cpp"))
        override fun start(context: LanguageServerContext): LanguageServerConnection =
            LanguageServerConnection.process(listOf(clangd!!, "--log=error", "--background-index=false"), context.root)
    }

    @BeforeTest
    fun setUp() {
        assumeTrue(clangd != null, "no clangd installed")
        root = Files.createTempDirectory("clangd-test").toRealPath()
        manager = LspSessionManager(object : LspSessionManager.Host {
            override fun projectRootFor(file: Path): Path? = root
        })
        support = LspLanguageSupport(definition, manager)
    }

    @AfterTest
    fun tearDown() {
        if (!::manager.isInitialized) return
        manager.close()
        root.toFile().deleteRecursively()
    }

    private val analyzer: SourceAnalyzer by lazy {
        support.backend.createAnalyzer(object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = emptyList()
        })
    }

    private fun snapshot(file: VirtualFile, text: String) = object : DocumentSnapshot {
        override val file = file
        override val version = 1L
        override val text: CharSequence = text
        override fun length() = text.length
    }

    private fun target(file: VirtualFile, text: String): AnalysisTarget {
        val parsed = analyzer.incrementalParser.parseFull(snapshot(file, text))
        return object : AnalysisTarget {
            override val file = file
            override val parsed = parsed
            override val documentVersion = 1L
            override val resolver: SourceAnalyzer get() = analyzer
            override val index: IndexService get() = error("unused")
            override val module: Module get() = error("unused")
            override fun checkCanceled() {}
        }
    }

    /** clangd answers once its first parse is done; a cold start can miss the first, short, budget. */
    private fun <T> eventually(block: () -> T?): T? {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    private val source = """
        int add(int a, int b) { return a + b; }
        int main() {
          int total = add(1, 2);
          zqxw;
          return total;
        }
    """.trimIndent() + "\n"

    @Test
    fun clangdServesTheEditorFeatures() {
        runBlocking {
            val path = root.resolve("main.cpp")
            path.writeText(source)
            val file = LspFile(path)

            val errors = assertNotNull(eventually {
                runBlocking { support.diagnostics.diagnose(target(file, source)) }.takeIf { it.isNotEmpty() }
            }, "clangd reported nothing")
            val undeclared = errors.first { it.severity == Severity.ERROR }
            assertEquals("zqxw", source.substring(undeclared.range.start, undeclared.range.end))

            val call = source.indexOf("add(1")
            val decl = support.declarations.declarations(target(file, source), call + 1).single()
            assertEquals(source.indexOf("add"), decl.offset)

            val doc = assertNotNull(analyzer.quickDoc(file, source, call + 1))
            assertTrue("add" in doc.signature || "add" in doc.doc.orEmpty(), doc.toString())

            val editing = source.replace("return total;", "return tot")
            val offset = editing.indexOf("return tot") + "return tot".length
            val params = CompletionParams.lazilyParsed(
                snapshot(file, editing), offset, "tot", LanguageId("cpp"), CompletionTrigger.Explicit,
                TextRange(offset - 3, offset), parse = { null },
            )
            val sink = BasicCompletionResultSet(params)
            support.completion.fillCompletionVariants(params, sink)
            assertTrue(sink.elements.any { it.label.trim() == "total" || it.insertText == "total" }, sink.elements.map { it.label }.toString())

            val prepared = assertNotNull(support.rename.prepare(target(file, source), source.indexOf("total") + 1))
            assertEquals("total", prepared.name)
            val renamed = assertNotNull(support.rename.rename(target(file, source), source.indexOf("total") + 1, "sum").edit)
            assertEquals(source.replace("total", "sum"), LspConvert.applyEdits(source, renamed.edits.values.single()))

            val structure = analyzer.fileStructure(file, source)
            assertEquals(listOf("add", "main"), structure.map { it.name }.filter { it == "add" || it == "main" })

            analyzer.incrementalParser.parseFull(snapshot(file, source))
            val tokens = analyzer.semanticHighlighter!!.highlight(file)
            assertTrue(
                tokens.any { source.substring(it.range.start, it.range.end) == "add" && it.kind == HighlightKind.FUNCTION },
                tokens.map { source.substring(it.range.start, it.range.end) + ":" + it.kind.id }.toString(),
            )
        }
    }
}
