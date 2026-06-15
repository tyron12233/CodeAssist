package dev.ide.analysis.impl

import dev.ide.analysis.AnalysisListener
import dev.ide.analysis.AnalysisProfile
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.analysis.DiagnosticTag
import dev.ide.analysis.FileAnalyzer
import dev.ide.analysis.FixContext
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.ProjectAnalyzer
import dev.ide.analysis.ProjectDiagnosticSink
import dev.ide.analysis.QuickFix
import dev.ide.analysis.QuickFixProvider
import dev.ide.analysis.WorkspaceEdit
import dev.ide.index.IndexService
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.model.Module
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisEngineTest {

    // ---- pipeline ----

    @Test
    fun analyzeNowMergesAnalyzerAndCompilerWithSources() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val analyzer = RecordingAnalyzer(AnalyzerId("style"), AnalyzerTier.SYNTAX, interestedIn = null, code = "style.x")
        val compiler = FakeCompiler { listOf(compilerDiag(it, "MISSING_SEMICOLON")) }
        val engine = engine(analyzers = listOf(analyzer), diagnosticProviders = listOf(compiler), env = env(target))

        val merged = engine.analyzeNow(file)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.source == DiagnosticSource.Analyzer(analyzer.id) && it.code == "style.x" })
        assertTrue(merged.any { it.source == DiagnosticSource.Compiler && it.code == "MISSING_SEMICOLON" })
        // diagnostics() serves the same published set without recomputing.
        assertEquals(2, engine.diagnostics(file).size)
    }

    @Test
    fun sharedTraversalGatesAnalyzersByNodeKind() = runBlocking {
        val file = FakeFile("/src/Main.java")
        // File has a CLASS_DECL but no METHOD_DECL.
        val target = target(file, "class Main {}", node(NodeKind.CLASS_DECL, 0, 13))
        val onClass = RecordingAnalyzer(AnalyzerId("a"), AnalyzerTier.SYNTAX, setOf(NodeKind.CLASS_DECL), "a")
        val onMethod = RecordingAnalyzer(AnalyzerId("b"), AnalyzerTier.SYNTAX, setOf(NodeKind.METHOD_DECL), "b")
        val engine = engine(analyzers = listOf(onClass, onMethod), env = env(target))

        engine.analyzeNow(file)

        assertEquals(1, onClass.invocations, "interested in a present kind ⇒ invoked")
        assertEquals(0, onMethod.invocations, "interested only in an absent kind ⇒ skipped (one shared walk gated it)")
    }

    @Test
    fun tierGateRequestsBindingsOnlyForEnabledSemanticAnalyzers() = runBlocking {
        val file = FakeFile("/src/Main.java")

        // SYNTAX-only ⇒ the engine must NOT ask the host for a binding-resolved tree (the cheap path).
        val synEnv = env(target(file, "class Main {}"))
        engine(analyzers = listOf(RecordingAnalyzer(AnalyzerId("s"), AnalyzerTier.SYNTAX, null, "s")), env = synEnv).analyzeNow(file)
        assertTrue(synEnv.bindingRequests.isEmpty(), "a SYNTAX-only file must not trigger a binding parse")

        // A SEMANTIC analyzer enabled ⇒ the engine requests a binding-resolved tree.
        val semEnv = env(target(file, "class Main {}"))
        engine(analyzers = listOf(RecordingAnalyzer(AnalyzerId("sem"), AnalyzerTier.SEMANTIC, null, "sem")), env = semEnv).analyzeNow(file)
        assertTrue(file.path in semEnv.bindingRequests, "a SEMANTIC analyzer must trigger a binding parse")

        // A *disabled* SEMANTIC analyzer ⇒ no binding tree (the gate respects the profile, not just the tier).
        val offEnv = env(target(file, "class Main {}"))
        val semId = AnalyzerId("sem")
        engine(
            analyzers = listOf(RecordingAnalyzer(semId, AnalyzerTier.SEMANTIC, null, "sem")),
            env = offEnv, profile = AnalysisProfile(disabled = setOf(semId)),
        ).analyzeNow(file)
        assertTrue(offEnv.bindingRequests.isEmpty(), "a disabled SEMANTIC analyzer must not trigger a binding parse")
    }

    @Test
    fun profileDisablesAndOverridesSeverity() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val a = RecordingAnalyzer(AnalyzerId("a"), AnalyzerTier.SYNTAX, null, "a", defaultSeverity = Severity.WARNING)
        val b = RecordingAnalyzer(AnalyzerId("b"), AnalyzerTier.SYNTAX, null, "b")
        val profile = AnalysisProfile(disabled = setOf(b.id), severityOverrides = mapOf(a.id to Severity.ERROR))
        val engine = engine(analyzers = listOf(a, b), env = env(target), profile = profile)

        val merged = engine.analyzeNow(file)

        assertEquals(1, merged.size, "disabled analyzer b produces nothing")
        assertEquals("a", merged.single().code)
        assertEquals(Severity.ERROR, merged.single().severity, "severity override applied over the reported WARNING")
        assertEquals(0, b.invocations)
    }

    @Test
    fun suppressionByAnnotationAndComment() = runBlocking {
        val file = FakeFile("/src/Main.java")
        // A method body spanning the whole snippet; @Suppress is inside it.
        val src = """
            class Main {
              @Suppress("style.x")
              void m() { bad(); }
            }
        """.trimIndent()
        val method = node(NodeKind.METHOD_DECL, src.indexOf("@Suppress"), src.length)
        val target = target(file, src, method)
        // analyzer reports inside the method (gets suppressed); compiler reports outside (not).
        val inMethod = method.range.start + 5
        val analyzer = RecordingAnalyzer(AnalyzerId("style"), AnalyzerTier.SYNTAX, null, "style.x", atRange = TextRange(inMethod, inMethod + 1))
        val compiler = FakeCompiler { listOf(Diagnostic(TextRange(0, 1), Severity.ERROR, "compiler", DiagnosticSource.Compiler, code = "OTHER")) }
        val engine = engine(analyzers = listOf(analyzer), diagnosticProviders = listOf(compiler), env = env(target))

        val merged = engine.analyzeNow(file)

        assertTrue(merged.none { it.code == "style.x" }, "@Suppress(\"style.x\") in the enclosing method suppresses it")
        assertTrue(merged.any { it.code == "OTHER" }, "an unrelated compiler diagnostic is untouched")
    }

    @Test
    fun noinspectionSuppressesFollowingLine() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val src = "class Main {\n  // noinspection style.x\n  int bad;\n}"
        val badOffset = src.indexOf("int bad;")
        val target = target(file, src, node(NodeKind.FIELD_DECL, badOffset, badOffset + 8))
        val analyzer = RecordingAnalyzer(AnalyzerId("style"), AnalyzerTier.SYNTAX, null, "style.x", atRange = TextRange(badOffset, badOffset + 3))
        val engine = engine(analyzers = listOf(analyzer), env = env(target))

        assertTrue(engine.analyzeNow(file).none { it.code == "style.x" })
    }

    // ---- quick-fixes ----

    @Test
    fun fixesForCombinesAuthoredAndProviderFixes() {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val authored = EditFix("authored", file)
        val providerFix = EditFix("imported", file)
        val provider = FakeFixProvider(setOf("UNRESOLVED_REFERENCE"), providerFix)
        val engine = engine(quickFixProviders = listOf(provider), env = env(target))

        val diag = Diagnostic(TextRange(0, 1), Severity.ERROR, "x", DiagnosticSource.Compiler, code = "UNRESOLVED_REFERENCE", fixes = listOf(authored))
        val fixes = engine.fixesFor(diag, target)

        assertEquals(listOf("authored", "imported"), fixes.map { it.title })
    }

    @Test
    fun applyRunsEditThroughEnvironmentAndReanalyzes() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val environment = env(target)
        val reAnalyzed = mutableListOf<VirtualFile>()
        val analyzer = object : FileAnalyzer {
            override val id = AnalyzerId("a"); override val displayName = "a"
            override val languages = setOf(LanguageId("java")); override val defaultSeverity = Severity.WARNING
            override val tier = AnalyzerTier.SYNTAX; override val interestedIn: Set<NodeKind>? = null
            override fun analyze(t: AnalysisTarget, sink: dev.ide.analysis.DiagnosticSink) { reAnalyzed += t.file }
        }
        val engine = engine(analyzers = listOf(analyzer), env = environment)

        val applied = engine.apply(EditFix("fix", file), FakeFixContext(target))

        assertEquals(1, environment.applied.size, "the edit went through AnalysisEnvironment.applyEdit")
        assertEquals(setOf(file as VirtualFile), applied.files)
        assertTrue(reAnalyzed.contains(file), "touched files are re-analyzed after applying")
    }

    @Test
    fun emptyFixIsNotApplied() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val environment = env(target(file, "class Main {}"))
        val engine = engine(env = environment)
        val noop = object : QuickFix {
            override val title = "noop"; override val kind = CodeActionKind.QUICK_FIX
            override suspend fun computeEdits(ctx: FixContext) = WorkspaceEdit.EMPTY
        }
        val applied = engine.apply(noop, FakeFixContext(target(file, "class Main {}")))
        assertTrue(applied.isEmpty)
        assertTrue(environment.applied.isEmpty())
    }

    // ---- batch lint ----

    @Test
    fun lintAggregatesFileAndProjectAnalyzers() = runBlocking {
        val a = FakeFile("/a.java")
        val b = FakeFile("/b.java")
        val targets = mapOf(a.path to target(a, "class A {}"), b.path to target(b, "class B {}"))
        val fileAnalyzer = RecordingAnalyzer(AnalyzerId("file"), AnalyzerTier.SEMANTIC, null, "file.x")
        val projectAnalyzer = FakeProjectAnalyzer(AnalyzerId("proj"), "proj.dup")
        val engine = engine(
            analyzers = listOf(fileAnalyzer, projectAnalyzer),
            env = env(*targets.values.toTypedArray()),
        )

        val report = engine.lint(FakeScope(targets))

        assertEquals(2, report.files.size)
        // each file: 1 file-analyzer finding + 1 project-analyzer finding
        assertEquals(4, report.all.size)
        assertTrue(report.all.any { it.code == "file.x" })
        assertTrue(report.all.any { it.code == "proj.dup" && it.source == DiagnosticSource.Analyzer(projectAnalyzer.id) })
    }

    // ---- scheduler ----

    @Test
    fun fileChangePublishesAllTiers() = runTest {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val syntax = RecordingAnalyzer(AnalyzerId("syn"), AnalyzerTier.SYNTAX, null, "syn")
        val semantic = RecordingAnalyzer(AnalyzerId("sem"), AnalyzerTier.SEMANTIC, null, "sem")
        val compiler = FakeCompiler { listOf(compilerDiag(it, "C")) }
        val engine = engine(
            analyzers = listOf(syntax, semantic), diagnosticProviders = listOf(compiler),
            env = env(target), scope = this,
        )

        engine.fileChanged(file)
        advanceUntilIdle()

        val codes = engine.diagnostics(file).map { it.code }.toSet()
        assertEquals(setOf("syn", "sem", "C"), codes)
    }

    @Test
    fun rapidEditsSupersedeThePriorPass() = runTest {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val syntax = RecordingAnalyzer(AnalyzerId("syn"), AnalyzerTier.SYNTAX, null, "syn")
        val semantic = RecordingAnalyzer(AnalyzerId("sem"), AnalyzerTier.SEMANTIC, null, "sem")
        val engine = engine(analyzers = listOf(syntax, semantic), env = env(target), scope = this)

        engine.fileChanged(file)   // immediately superseded (its job is cancelled before it runs)
        engine.fileChanged(file)
        advanceUntilIdle()

        assertEquals(1, syntax.invocations)
        assertEquals(1, semantic.invocations)
    }

    @Test
    fun listenerNotifiedWithMergedSet() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val target = target(file, "class Main {}")
        val analyzer = RecordingAnalyzer(AnalyzerId("a"), AnalyzerTier.SYNTAX, null, "a")
        val engine = engine(analyzers = listOf(analyzer), env = env(target))
        var last: List<Diagnostic>? = null
        engine.addListener(AnalysisListener { _, d -> last = d })

        engine.analyzeNow(file)

        assertEquals("a", last?.singleOrNull()?.code)
    }

    @Test
    fun unanalyzableFileClearsPublishedSet() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val engine = engine(env = env()) // env returns null target for everything
        assertTrue(engine.analyzeNow(file).isEmpty())
        assertNull(engine.diagnostics(file).firstOrNull())
    }

    // ---- factories ----

    private fun engine(
        analyzers: List<dev.ide.analysis.Analyzer> = emptyList(),
        quickFixProviders: List<QuickFixProvider> = emptyList(),
        diagnosticProviders: List<DiagnosticProvider> = emptyList(),
        env: FakeEnv,
        profile: AnalysisProfile = AnalysisProfile.DEFAULT,
        scope: CoroutineScope = CoroutineScope(Job()),
    ) = AnalysisEngine(analyzers, quickFixProviders, diagnosticProviders, env, scope, profile, SchedulerConfig(0, 0, 0))

    private fun env(vararg targets: AnalysisTarget) =
        FakeEnv(targets.associateBy { it.file.path }.toMutableMap())

    private fun target(file: VirtualFile, src: String, vararg nodes: FakeNode) =
        FakeTarget(file, FakeParsed(file, version = 1, src = src, top = nodes.toList()))

    private fun node(kind: NodeKind, start: Int, end: Int) = FakeNode(kind, TextRange(start, end))

    private fun compilerDiag(target: AnalysisTarget, code: String) =
        Diagnostic(TextRange(0, 1), Severity.ERROR, "compiler error in ${target.file.name}", DiagnosticSource.Compiler, code = code)
}

// --------------------------------------------------------------------------- fakes

private class RecordingAnalyzer(
    override val id: AnalyzerId,
    override val tier: AnalyzerTier,
    override val interestedIn: Set<NodeKind>?,
    private val code: String,
    override val defaultSeverity: Severity = Severity.WARNING,
    private val atRange: TextRange = TextRange(0, 1),
) : FileAnalyzer {
    override val displayName = id.value
    override val languages = setOf(LanguageId("java"))
    var invocations = 0; private set
    override fun analyze(target: AnalysisTarget, sink: dev.ide.analysis.DiagnosticSink) {
        invocations++
        sink.report(atRange, defaultSeverity, "finding", code = code)
    }
}

private class FakeCompiler(
    override val id: String = "compiler",
    private val produce: (AnalysisTarget) -> List<Diagnostic>,
) : DiagnosticProvider {
    override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> = produce(target)
}

private class FakeProjectAnalyzer(override val id: AnalyzerId, private val code: String) : ProjectAnalyzer {
    override val displayName = id.value
    override val languages = setOf(LanguageId("java"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.PROJECT
    override suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink) {
        for (f in scope.files()) sink.report(f, TextRange(0, 1), defaultSeverity, "dup", code = code)
    }
}

private class FakeFixProvider(override val forCodes: Set<String>, private val fix: QuickFix) : QuickFixProvider {
    override fun fixes(diagnostic: Diagnostic, target: AnalysisTarget): List<QuickFix> = listOf(fix)
}

private class EditFix(override val title: String, private val file: VirtualFile) : QuickFix {
    override val kind = CodeActionKind.QUICK_FIX
    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit =
        WorkspaceEdit.of(file, DocumentEdit(0, 0, "x"))
}

private class FakeFixContext(override val target: AnalysisTarget) : FixContext {
    override fun checkCanceled() {}
}

private class FakeEnv(
    private val targets: MutableMap<String, AnalysisTarget>,
    private val language: LanguageId? = LanguageId("java"),
    private val scopeProvider: () -> ProjectAnalysisScope = { FakeScope(emptyMap()) },
) : AnalysisEnvironment {
    val applied = mutableListOf<WorkspaceEdit>()
    val bindingRequests = mutableListOf<String>() // files for which the engine requested a binding tree
    override suspend fun targetFor(file: VirtualFile, needsBindings: Boolean): AnalysisTarget? {
        if (needsBindings) bindingRequests += file.path
        return targets[file.path]
    }
    override fun languageOf(file: VirtualFile): LanguageId? = language
    override fun projectScope(): ProjectAnalysisScope = scopeProvider()
    override suspend fun applyEdit(edit: WorkspaceEdit): WorkspaceEdit { applied += edit; return edit }
}

private class FakeScope(private val targets: Map<String, AnalysisTarget>) : ProjectAnalysisScope {
    override val modules: List<Module> get() = emptyList()
    override val index: IndexService get() = error("index unused in these tests")
    override fun files(): Sequence<VirtualFile> = targets.values.map { it.file }.asSequence()
    override suspend fun targetFor(file: VirtualFile): AnalysisTarget = targets.getValue(file.path)
    override fun checkCanceled() {}
}

private class FakeTarget(
    override val file: VirtualFile,
    override val parsed: ParsedFile,
    override val documentVersion: Long = parsed.documentVersion,
) : AnalysisTarget {
    override val resolver: SourceAnalyzer get() = error("resolver unused in these tests")
    override val index: IndexService get() = error("index unused in these tests")
    override val module: Module get() = error("module unused in these tests")
    override fun checkCanceled() {}
}

private class FakeNode(
    override val kind: NodeKind,
    override val range: TextRange,
    override val children: List<FakeNode> = emptyList(),
) : DomNode {
    override var parent: DomNode? = null
    init { children.forEach { it.parent = this } }
    override fun text(): CharSequence = ""
}

private class FakeParsed(
    override val file: VirtualFile,
    private val version: Int,
    private val src: String,
    private val top: List<FakeNode>,
) : ParsedFile {
    override val kind = NodeKind.COMPILATION_UNIT
    override val range = TextRange(0, src.length)
    override val parent: DomNode? = null
    override val children: List<DomNode> = top
    override val documentVersion: Long = version.toLong()
    override val diagnostics: List<dev.ide.lang.dom.Diagnostic> = emptyList()
    init { top.forEach { it.parent = this } }
    override fun text(): CharSequence = src
    override fun nodeAt(offset: Int): DomNode {
        var best: DomNode = this
        fun visit(n: DomNode) { if (offset in n.range) { best = n; n.children.forEach(::visit) } }
        children.forEach(::visit)
        return best
    }
    override fun nodesIn(range: TextRange): Sequence<DomNode> {
        val out = ArrayList<DomNode>()
        fun walk(n: DomNode) { if (n.range.intersects(range)) { out += n; n.children.forEach(::walk) } }
        children.forEach(::walk)
        return out.asSequence()
    }
}

private class FakeFile(override val path: String) : VirtualFile {
    override val name = path.substringAfterLast('/')
    override val isDirectory = false
    override val exists = true
    override val length = 0L
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash() = ContentHash("")
    override fun readBytes() = ByteArray(0)
    override fun readText(): CharSequence = ""
}
