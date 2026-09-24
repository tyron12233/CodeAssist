package dev.ide.analysis.impl

import dev.ide.analysis.ActionProvider
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
import dev.ide.analysis.NodeIndex
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
import dev.ide.platform.PluginId
import dev.ide.testkit.InMemoryVirtualFile
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
    fun fileSuppressCoversTheWholeFileIncludingImports() = runBlocking {
        val file = FakeFile("/src/Main.java")
        // `@file:Suppress("style.x")` at the very top; the finding sits BEFORE any declaration (like an
        // unused-import / package-line warning) — a spot a declaration-scoped @Suppress can never reach.
        val src = "@file:Suppress(\"style.x\")\npackage demo;\nimport a.b.C;\nclass Main {}"
        val at = TextRange(src.indexOf("import"), src.indexOf("import") + 6)
        val target = target(file, src) // no declaration nodes — whole-file scope must still cover it
        val suppressed = RecordingAnalyzer(AnalyzerId("sx"), AnalyzerTier.SYNTAX, null, "style.x", atRange = at)
        val other = RecordingAnalyzer(AnalyzerId("sy"), AnalyzerTier.SYNTAX, null, "style.y", atRange = at)
        val engine = engine(analyzers = listOf(suppressed, other), env = env(target))

        val merged = engine.analyzeNow(file)
        assertTrue(merged.none { it.code == "style.x" }, "@file:Suppress(\"style.x\") covers the whole file, imports included")
        assertTrue(merged.any { it.code == "style.y" }, "a code not listed in @file:Suppress is untouched")
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

    @Test
    fun wholeFileAnalyzersNeverWalkTheDom() = runBlocking {
        // Every ide-core analyzer is a whole-file one (`interestedIn == null`), so on a Kotlin file the
        // gate has nothing to gate: the pass must not pay a traversal to discover that.
        val file = FakeFile("/src/Main.java")
        val parsed = FakeParsed(file, version = 1, src = "class Main {}", top = listOf(node(NodeKind.CLASS_DECL, 0, 13)))
        val target = FakeTarget(file, parsed)
        val whole = RecordingAnalyzer(AnalyzerId("whole"), AnalyzerTier.SYNTAX, interestedIn = null, code = "w")
        val engine = engine(analyzers = listOf(whole), env = env(target))

        engine.analyzeNow(file)

        assertEquals(1, whole.invocations, "the whole-file analyzer should still run")
        assertEquals(0, parsed.walks, "a pass with only whole-file analyzers must not traverse the DOM")
    }

    @Test
    fun nodeKeyedAnalyzersShareOneTraversal() = runBlocking {
        // Two analyzers keyed on the same kind, plus a whole-file one: ONE walk for the pass, and each
        // node-keyed analyzer is handed its nodes rather than walking to find them.
        val file = FakeFile("/src/Main.java")
        val src = "class Main { void a() { f(); } void b() { g(); } }"
        val calls = listOf(node(NodeKind.METHOD_CALL, 24, 27), node(NodeKind.METHOD_CALL, 41, 44))
        val parsed = FakeParsed(file, version = 1, src = src, top = calls + node(NodeKind.CLASS_DECL, 0, src.length))
        val target = FakeTarget(file, parsed)
        val first = NodeKeyedAnalyzer(AnalyzerId("first"), NodeKind.METHOD_CALL)
        val second = NodeKeyedAnalyzer(AnalyzerId("second"), NodeKind.METHOD_CALL)
        val whole = RecordingAnalyzer(AnalyzerId("whole"), AnalyzerTier.SYNTAX, interestedIn = null, code = "w")
        val engine = engine(analyzers = listOf(first, second, whole), env = env(target))

        engine.analyzeNow(file)

        assertEquals(1, parsed.walks, "the pass must traverse the DOM exactly once for both analyzers")
        assertEquals(listOf(2, 2), listOf(first.seen, second.seen), "each analyzer should get both calls")
    }

    @Test
    fun anAnalyzerWithNoNodeOfItsKindIsSkipped() = runBlocking {
        val file = FakeFile("/src/Main.java")
        val parsed = FakeParsed(file, version = 1, src = "class Main {}", top = listOf(node(NodeKind.CLASS_DECL, 0, 13)))
        val target = FakeTarget(file, parsed)
        val keyed = NodeKeyedAnalyzer(AnalyzerId("calls"), NodeKind.METHOD_CALL)
        val engine = engine(analyzers = listOf(keyed), env = env(target))

        engine.analyzeNow(file)

        assertEquals(0, keyed.invocations, "no METHOD_CALL in the file -> the analyzer must not run")
        assertEquals(1, parsed.walks, "the gate pays one walk to answer that")
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
    fun editorActionsDropAnIntentionAlreadyOfferedAsAFix() = runBlocking {
        // The overlap by design: an action offered across a whole declaration, and the same action offered
        // by a code-keyed provider on the error inside it. On the error both apply; the menu must not show
        // the row twice (this was two "Implement members" entries on a Kotlin class name).
        val file = FakeFile("/src/Main.java")
        val src = "class Main implements Service {}"
        val onName = TextRange(src.indexOf("Main"), src.indexOf("Main") + 4)
        val target = target(file, src, node(NodeKind.CLASS_DECL, 0, src.length))
        val analyzer = RecordingAnalyzer(
            AnalyzerId("inheritance"), AnalyzerTier.SYNTAX, null, "ABSTRACT_NOT_IMPLEMENTED",
            Severity.ERROR, onName,
        )
        val engine = engine(
            analyzers = listOf(analyzer),
            quickFixProviders = listOf(
                FakeFixProvider(setOf("ABSTRACT_NOT_IMPLEMENTED"), EditFix("Implement members", file)),
            ),
            env = env(target),
            actionProviders = listOf(
                FakeActionProvider(
                    EditFix("Implement members", file, CodeActionKind.REFACTOR),
                    EditFix("Convert to block body", file, CodeActionKind.REFACTOR, text = "block"),
                ),
            ),
        )
        engine.analyzeNow(file) // publish the diagnostic the fix hangs off

        val caret = TextRange(onName.start, onName.start)
        val actions = engine.editorActionsAt(file, caret)

        assertEquals(listOf("Implement members", "Convert to block body"), actions.map { it.title })
        // The row that survives is the FIX (the one anchored on the error), not the intention.
        assertEquals(CodeActionKind.QUICK_FIX, actions.first().kind)
        // And the index round-trip agrees with the list the host was shown: index 1 is the other intention.
        val edits = engine.computeActionEdits(file, caret, 1).edits.getValue(file)
        assertEquals("block", edits.single().newText.toString())
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

    // ---- host-driven project sweep ----

    @Test
    fun analyzeProjectPublishesProjectFindingsAndReportsChangedFiles() {
        runBlocking {
            val a = FakeFile("/a.kt")
            val b = FakeFile("/b.kt")
            val targets = mapOf(a.path to target(a, "class A"), b.path to target(b, "class B"))
            val projectAnalyzer = FakeProjectAnalyzer(AnalyzerId("proj"), "proj.dup")
            val engine = engine(
                analyzers = listOf(projectAnalyzer),
                env = FakeEnv(targets.toMutableMap(), scopeProvider = { FakeScope(targets) }),
            )
            assertTrue(engine.hasProjectWork)

            val changed = engine.analyzeProject()

            assertEquals(setOf(a.path, b.path), changed.map { it.path }.toSet())
            assertEquals(listOf("proj.dup"), engine.diagnostics(a).mapNotNull { it.code })
            assertTrue(engine.analyzeProject().isEmpty(), "an unchanged second sweep changes nothing")
        }
    }

    @Test
    fun analyzeProjectContainsAThrowingPluginProjectAnalyzer() {
        runBlocking {
            val a = FakeFile("/a.kt")
            val targets = mapOf(a.path to target(a, "class A"))
            val good = FakeProjectAnalyzer(AnalyzerId("good"), "good")
            val bad = object : ProjectAnalyzer {
                override val id = AnalyzerId("bad")
                override val displayName = "bad"
                override val languages = setOf(LanguageId("kotlin"))
                override val defaultSeverity = Severity.WARNING
                override val tier = AnalyzerTier.PROJECT
                override suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink) {
                    sink.report(a, TextRange(0, 1), defaultSeverity, "partial", code = "bad")
                    error("plugin bug")
                }
            }
            val engine = engine(
                analyzers = listOf(bad, good),
                env = FakeEnv(targets.toMutableMap(), scopeProvider = { FakeScope(targets) }),
                externalAnalyzers = mapOf(bad.id to PluginId("acme")),
            )

            engine.analyzeProject()

            assertEquals(listOf("good"), engine.diagnostics(a).mapNotNull { it.code })
        }
    }

    @Test
    fun noProjectWorkWithoutProjectAnalyzers() {
        val engine = engine(env = env())
        assertTrue(!engine.hasProjectWork)
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

    // ---- installed-plugin analyzers (ordering, isolation, the time budget) ----

    @Test
    fun builtInFindingsPublishBeforeASlowPluginAnalyzerRuns() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            var engine: AnalysisEngine? = null
            var seenMidPass: List<String> = emptyList()
            val builtIn = RecordingAnalyzer(AnalyzerId("host"), AnalyzerTier.SYNTAX, null, "host")
            // Reads what the editor would already be showing at the moment the plugin's analyzer runs.
            val plugin = PluginAnalyzer(AnalyzerId("slow")) { sink ->
                seenMidPass = engine!!.diagnostics(file).mapNotNull { it.code }
                sink.report(TextRange(0, 1), Severity.WARNING, "plugin finding", code = "slow")
            }
            engine = engine(
                analyzers = listOf(builtIn, plugin), env = env(target),
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")),
            )

            val merged = engine.analyzeNow(file).mapNotNull { it.code }

            assertEquals(listOf("host"), seenMidPass, "the host's half of the pass is published before the plugin's runs")
            assertEquals(setOf("host", "slow"), merged.toSet(), "both halves end up in the merged set")
        }
    }

    @Test
    fun aThrowingPluginAnalyzerCostsOnlyItsOwnFindings() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            val builtIn = RecordingAnalyzer(AnalyzerId("host"), AnalyzerTier.SYNTAX, null, "host")
            val plugin = PluginAnalyzer(AnalyzerId("boom")) { sink ->
                sink.report(TextRange(0, 1), Severity.WARNING, "half a result", code = "boom")
                error("plugin analyzer blew up")
            }
            val compiler = FakeCompiler { listOf(compilerDiag(it, "MISSING_SEMICOLON")) }
            val engine = engine(
                analyzers = listOf(builtIn, plugin), diagnosticProviders = listOf(compiler), env = env(target),
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")),
            )

            val merged = engine.analyzeNow(file).mapNotNull { it.code }

            assertEquals(
                setOf("host", "MISSING_SEMICOLON"), merged.toSet(),
                "the throw costs the user that check; the host's analyzers and the compiler still publish",
            )
        }
    }

    @Test
    fun aPluginAnalyzerOverItsBudgetIsQuarantinedAfterRepeatedPasses() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            val clock = FakeClock()
            val plugin = PluginAnalyzer(AnalyzerId("slow")) { sink ->
                clock.advanceMs(200) // way over the 25ms SYNTAX budget
                sink.report(TextRange(0, 1), Severity.WARNING, "finding", code = "slow")
            }
            val engine = engine(
                analyzers = listOf(plugin), env = env(target),
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")), nanoTime = clock::read,
            )

            repeat(3) { engine.analyzeNow(file) }
            assertEquals(3, plugin.invocations, "three strikes are spent before it is dropped")
            assertTrue(engine.analyzeNow(file).isEmpty(), "quarantined: its findings are gone too")
            assertEquals(3, plugin.invocations, "a quarantined analyzer is not invoked again")

            val report = engine.quarantinedAnalyzers.single()
            assertEquals(PluginId("acme"), report.plugin, "the report names the plugin, not just the check")
            assertEquals(QuarantinedAnalyzer.Cause.SLOW, report.cause)
            assertEquals(200L, report.tookMs)
        }
    }

    @Test
    fun aPassInsideBudgetClearsTheStrikes() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            val clock = FakeClock()
            var slow = true
            val plugin = PluginAnalyzer(AnalyzerId("spiky")) { sink ->
                clock.advanceMs(if (slow) 200 else 1)
                sink.report(TextRange(0, 1), Severity.WARNING, "finding", code = "spiky")
            }
            val engine = engine(
                analyzers = listOf(plugin), env = env(target),
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")), nanoTime = clock::read,
            )

            // Two bad passes, one good one, then two more bad ones: strikes must be consecutive, so an
            // analyzer that is merely slow on the occasional pathological file is never dropped.
            repeat(2) { engine.analyzeNow(file) }
            slow = false; engine.analyzeNow(file)
            slow = true; repeat(2) { engine.analyzeNow(file) }

            assertEquals(5, plugin.invocations)
            assertTrue(engine.quarantinedAnalyzers.isEmpty(), "the in-budget pass reset the count")
        }
    }

    @Test
    fun configureReleasesTheQuarantine() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            val clock = FakeClock()
            val plugin = PluginAnalyzer(AnalyzerId("slow")) { sink ->
                clock.advanceMs(200)
                sink.report(TextRange(0, 1), Severity.WARNING, "finding", code = "slow")
            }
            val engine = engine(
                analyzers = listOf(plugin), env = env(target),
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")), nanoTime = clock::read,
            )
            repeat(4) { engine.analyzeNow(file) }
            assertEquals(3, plugin.invocations)

            engine.configure(AnalysisProfile.DEFAULT)

            assertTrue(engine.quarantinedAnalyzers.isEmpty(), "the user revisiting the profile is the way back")
            engine.analyzeNow(file)
            assertEquals(4, plugin.invocations, "released: it runs again (and can earn the quarantine back)")
        }
    }

    @Test
    fun aQuarantinedSemanticAnalyzerStopsForcingBindings() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val fakeEnv = env(target(file, "class Main {}"))
            val clock = FakeClock()
            val plugin = PluginAnalyzer(AnalyzerId("slow"), AnalyzerTier.SEMANTIC) { sink ->
                clock.advanceMs(500) // over the 120ms SEMANTIC budget
                sink.report(TextRange(0, 1), Severity.WARNING, "finding", code = "slow")
            }
            val engine = engine(
                analyzers = listOf(plugin), env = fakeEnv,
                externalAnalyzers = mapOf(plugin.id to PluginId("acme")), nanoTime = clock::read,
            )

            repeat(3) { engine.analyzeNow(file) }
            assertTrue(fakeEnv.bindingRequests.isNotEmpty(), "while it ran, the file paid for a binding tree")
            fakeEnv.bindingRequests.clear()
            engine.analyzeNow(file)

            assertTrue(
                fakeEnv.bindingRequests.isEmpty(),
                "the only SEMANTIC analyzer is gone, so the file drops back to the cheap syntax-only tree",
            )
        }
    }

    @Test
    fun theHostsOwnAnalyzersAreNeverQuarantined() {
        runBlocking {
            val file = FakeFile("/src/Main.java")
            val target = target(file, "class Main {}")
            val clock = FakeClock()
            // Same behaviour as the quarantined plugin above, but contributed by the IDE itself: a slow
            // built-in is our bug to fix, never something the watchdog may switch off behind the user.
            val builtIn = PluginAnalyzer(AnalyzerId("host")) { sink ->
                clock.advanceMs(5_000)
                sink.report(TextRange(0, 1), Severity.WARNING, "finding", code = "host")
            }
            val engine = engine(analyzers = listOf(builtIn), env = env(target), nanoTime = clock::read)

            repeat(5) { engine.analyzeNow(file) }

            assertEquals(5, builtIn.invocations)
            assertTrue(engine.quarantinedAnalyzers.isEmpty())
        }
    }

    // ---- factories ----

    private fun engine(
        analyzers: List<dev.ide.analysis.Analyzer> = emptyList(),
        quickFixProviders: List<QuickFixProvider> = emptyList(),
        diagnosticProviders: List<DiagnosticProvider> = emptyList(),
        env: FakeEnv,
        profile: AnalysisProfile = AnalysisProfile.DEFAULT,
        scope: CoroutineScope = CoroutineScope(Job()),
        actionProviders: List<ActionProvider> = emptyList(),
        externalAnalyzers: Map<AnalyzerId, PluginId> = emptyMap(),
        budget: AnalyzerBudget = AnalyzerBudget.DEFAULT,
        nanoTime: () -> Long = System::nanoTime,
    ) = AnalysisEngine(
        analyzers, quickFixProviders, diagnosticProviders, env, scope, profile, SchedulerConfig(0, 0, 0),
        actionProviders, externalAnalyzers, budget, nanoTime,
    )

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

/** An analyzer keyed on one [NodeKind], consuming the pass's shared traversal. */
private class NodeKeyedAnalyzer(override val id: AnalyzerId, private val kind: NodeKind) : FileAnalyzer {
    override val displayName = id.value
    override val languages = setOf(LanguageId("java"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn = setOf(kind)
    var invocations = 0; private set
    var seen = 0; private set

    override fun analyze(target: AnalysisTarget, sink: dev.ide.analysis.DiagnosticSink) =
        error("the engine must call the NodeIndex overload")

    override fun analyze(target: AnalysisTarget, sink: dev.ide.analysis.DiagnosticSink, nodes: NodeIndex) {
        invocations++
        for (n in nodes.nodes(kind)) { seen++; sink.report(n.range, defaultSeverity, "found", code = id.value) }
    }
}

/** An analyzer whose body a test supplies — the shape an installed plugin's check has here. */
private class PluginAnalyzer(
    override val id: AnalyzerId,
    override val tier: AnalyzerTier = AnalyzerTier.SYNTAX,
    private val body: (dev.ide.analysis.DiagnosticSink) -> Unit,
) : FileAnalyzer {
    override val displayName = id.value
    override val languages = setOf(LanguageId("java"))
    override val defaultSeverity = Severity.WARNING
    override val interestedIn: Set<NodeKind>? = null
    var invocations = 0; private set
    override fun analyze(target: AnalysisTarget, sink: dev.ide.analysis.DiagnosticSink) {
        invocations++
        body(sink)
    }
}

/** The watchdog's clock, advanced by the analyzer under test so no real time has to pass. */
private class FakeClock {
    private var nanos = 0L
    fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    fun read(): Long = nanos
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

private class EditFix(
    override val title: String,
    private val file: VirtualFile,
    override val kind: CodeActionKind = CodeActionKind.QUICK_FIX,
    private val text: String = "x",
) : QuickFix {
    override suspend fun computeEdits(ctx: FixContext): WorkspaceEdit =
        WorkspaceEdit.of(file, DocumentEdit(0, 0, text))
}

private class FakeActionProvider(private vararg val offered: QuickFix) : ActionProvider {
    override val languages = setOf(LanguageId("java"))
    override fun actions(target: AnalysisTarget, range: TextRange): List<QuickFix> = offered.toList()
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
    /** How many full-file traversals were asked of this parse: the engine's shared-walk guard. */
    var walks = 0; private set
    override fun text(): CharSequence = src
    override fun nodeAt(offset: Int): DomNode {
        var best: DomNode = this
        fun visit(n: DomNode) { if (offset in n.range) { best = n; n.children.forEach(::visit) } }
        children.forEach(::visit)
        return best
    }
    override fun nodesIn(range: TextRange): Sequence<DomNode> {
        if (range == this.range) walks++
        val out = ArrayList<DomNode>()
        fun walk(n: DomNode) { if (n.range.intersects(range)) { out += n; n.children.forEach(::walk) } }
        children.forEach(::walk)
        return out.asSequence()
    }
}

/** A bare [VirtualFile] backed only by a path — enough for these engine tests. */
private typealias FakeFile = InMemoryVirtualFile
