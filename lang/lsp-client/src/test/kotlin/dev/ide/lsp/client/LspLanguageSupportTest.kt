package dev.ide.lsp.client

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.CodeActionKind
import dev.ide.analysis.EditorActionContext
import dev.ide.analysis.FixContext
import dev.ide.analysis.NavigationKind
import dev.ide.index.IndexService
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.BasicCompletionResultSet
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.highlight.HighlightKind
import dev.ide.lang.highlight.HighlightModifier
import dev.ide.lang.highlight.UsageAccess
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.resolve.DocFormat
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.signature.SignatureHelpRequest
import dev.ide.lang.signature.SignatureHelpTrigger
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspLanguageSupportTest {
    private lateinit var root: Path
    private lateinit var server: FakeLanguageServer
    private lateinit var manager: LspSessionManager
    private lateinit var support: LspLanguageSupport
    private val changed = java.util.concurrent.CopyOnWriteArrayList<Set<Path>>()
    private val applied = java.util.concurrent.CopyOnWriteArrayList<Map<Path, List<dev.ide.lang.incremental.DocumentEdit>>>()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("lsp-test").toRealPath()
        server = FakeLanguageServer()
        manager = LspSessionManager(object : LspSessionManager.Host {
            override fun projectRootFor(file: Path): Path? = root.takeIf { file.toAbsolutePath().startsWith(it) }
            override fun diagnosticsChanged(paths: Set<Path>) { changed += paths }
            override fun applyEdit(session: LspSession, edit: kotlinx.serialization.json.JsonObject): Boolean {
                applied += session.documentEdits(edit)
                return true
            }
        })
        support = LspLanguageSupport(server.definition, manager)
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        root.toFile().deleteRecursively()
    }

    private val analyzer: SourceAnalyzer by lazy {
        support.backend.createAnalyzer(object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = emptyList()
        })
    }

    private fun file(name: String, text: String): VirtualFile {
        val p = root.resolve(name)
        p.writeText(text)
        return LspFile(p)
    }

    private fun snapshot(file: VirtualFile, text: String) = object : DocumentSnapshot {
        override val file = file
        override val version = 1L
        override val text: CharSequence = text
        override fun length() = text.length
    }

    /** What the host does before asking a service that is handed only the file: parse the live buffer. */
    private fun parse(file: VirtualFile, text: String) = analyzer.incrementalParser.parseFull(snapshot(file, text))

    private fun target(file: VirtualFile, text: String): AnalysisTarget {
        val parsed = parse(file, text)
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

    private val sample = "fun greet() {\n  bad\n}\ngreet()\n"

    @Test
    fun diagnosticsArriveForTheTextThatWasAnalyzed() {
        runBlocking {
            val f = file("a.toy", sample)
            val first = support.diagnostics.diagnose(target(f, sample))
            assertEquals(1, first.size)
            val d = first.single()
            assertEquals(Severity.ERROR, d.severity)
            assertEquals("toy.bad", d.code)
            assertEquals("bad", sample.substring(d.range.start, d.range.end))

            // An edit is sent as didChange and the answer is the one for the new text, not the stale one.
            val fixed = sample.replace("bad", "ok ")
            assertEquals(emptyList(), support.diagnostics.diagnose(target(f, fixed)))
            assertEquals(fixed, server.texts.values.single())
        }
    }

    @Test
    fun diagnosticsPushedOutsideAnAnalysisAreAnnounced() {
        runBlocking {
            val f = file("a.toy", sample)
            support.diagnostics.diagnose(target(f, sample))
            changed.clear()
            // The server re-publishes on its own (as after a dependency changed) with nobody waiting.
            val uri = uriOf(Path.of(f.path))
            server.connection!!.notify("textDocument/publishDiagnostics", kotlinx.serialization.json.buildJsonObject {
                put("uri", kotlinx.serialization.json.JsonPrimitive(uri))
                put("diagnostics", kotlinx.serialization.json.JsonArray(emptyList()))
            })
            val deadline = System.currentTimeMillis() + 3_000
            while (changed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(setOf(Path.of(f.path)), changed.firstOrNull())
        }
    }

    @Test
    fun completionExpandsSnippetsAndKeepsServerOrder() {
        runBlocking {
            val text = "fun greet() {}\nfun grow() {}\ngr"
            val f = file("c.toy", text)
            val params = CompletionParams.lazilyParsed(
                snapshot(f, text), text.length, "gr", LanguageId("toy"), CompletionTrigger.Explicit,
                TextRange(text.length - 2, text.length), parse = { null },
            )
            val sink = BasicCompletionResultSet(params)
            support.completion.fillCompletionVariants(params, sink)
            val labels = sink.elements.map { it.label }
            // `fun` does not match the prefix and is dropped; the two declared names remain, in sortText order.
            assertEquals(listOf("greet", "grow"), labels)
            val greet = sink.elements.first()
            assertEquals("greet()", greet.insertText)
            val caret = greet.caret as CaretAction.ExpandSnippet
            assertEquals(6, caret.expansion.stops.first { it.index == 1 }.ranges.single().start)
        }
    }

    @Test
    fun hoverBecomesQuickDocumentation() {
        val text = "fun greet() {}\ngreet()"
        val f = file("h.toy", text)
        val doc = assertNotNull(analyzer.quickDoc(f, text, text.length - 3))
        assertEquals("fun greet()", doc.signature)
        assertEquals("greet", doc.name)
        assertEquals(SymbolKind.METHOD, doc.kind)
        assertEquals("Declares **greet**.", doc.doc)
        assertEquals(DocFormat.MARKDOWN, doc.docFormat)
    }

    @Test
    fun signatureHelpMapsBothParameterLabelForms() {
        runBlocking {
            val text = "call(a, "
            val f = file("s.toy", text)
            val help = assertNotNull(
                analyzer.signatureHelp!!.signatureHelp(SignatureHelpRequest(snapshot(f, text), text.length, SignatureHelpTrigger.Explicit)),
            )
            val sig = help.signatures.single()
            assertEquals(listOf("a", "b"), sig.parameters.map { it.label })
            assertEquals(5, sig.parameters[0].labelStart)
            assertEquals(8, sig.parameters[1].labelStart)
            assertEquals(1, help.activeParameter)
        }
    }

    @Test
    fun navigationReferencesAndHighlights() {
        runBlocking {
            val text = "fun greet() {}\ngreet()\ngreet()"
            val f = file("n.toy", text)
            val use = text.lastIndexOf("greet")
            val decl = support.declarations.declarations(target(f, text), use).single()
            assertEquals(4, decl.offset)
            assertEquals(f.path, decl.path)
            assertEquals("n.toy:1", decl.label)

            val refs = support.references.references(target(f, text), use, includeDeclaration = true)
            assertEquals(3, refs.size)
            assertTrue(refs.all { text.substring(it.range.start, it.range.end) == "greet" })

            val highlights = assertNotNull(support.references.highlights(target(f, text), use))
            assertEquals(3, highlights.size)
            assertTrue(highlights.all { it.access == UsageAccess.READ })

            // The toy server has no implementation provider: empty, at once.
            assertEquals(emptyList(), support.navigation.targets(target(f, text), use, NavigationKind.IMPLEMENTATION))
        }
    }

    @Test
    fun renamePreparesThenEditsEveryOccurrence() {
        runBlocking {
            val text = "fun greet() {}\ngreet()"
            val f = file("r.toy", text)
            val t = target(f, text)
            val prepared = assertNotNull(support.rename.prepare(t, 6))
            assertEquals("greet", prepared.name)
            assertEquals(TextRange(4, 9), prepared.range)

            val result = support.rename.rename(t, 6, "hello")
            val edit = assertNotNull(result.edit)
            val edits = edit.edits.entries.single()
            assertEquals(f.path, edits.key.path)
            assertEquals("fun hello() {}\nhello()", LspConvert.applyEdits(text, edits.value))

            val rejected = support.rename.rename(t, 6, "fun")
            assertNull(rejected.edit)
            assertEquals("'fun' is a keyword", rejected.error)
        }
    }

    @Test
    fun codeActionRunsItsCommandAndReturnsTheEditTheServerAskedFor() {
        runBlocking {
            val f = file("q.toy", sample)
            val t = target(f, sample)
            support.diagnostics.diagnose(t) // so the action request carries the diagnostic
            val at = sample.indexOf("bad")
            val actions = support.codeActions.actions(EditorActionContext.of(t, TextRange(at, at)))
            assertEquals(listOf("Replace with 'good'", "Say hello"), actions.map { it.title })
            assertEquals(CodeActionKind.QUICK_FIX, actions[0].kind)
            assertEquals(CodeActionKind.REFACTOR, actions[1].kind)
            val edit = actions[0].computeEdits(object : FixContext {
                override val target = t
                override fun checkCanceled() {}
            })
            val entry = edit.edits.entries.single()
            // The focal file comes back as the target's own VirtualFile, so the host edits the open buffer.
            assertTrue(entry.key === f)
            assertEquals(sample.replace("bad", "good"), LspConvert.applyEdits(sample, entry.value))
        }
    }

    @Test
    fun editorServicesReadTheParsedBuffer() {
        runBlocking {
            val text = "fun greet() {\n  x\n}\n"
            val f = file("e.toy", text)
            parse(f, text)

            val tokens = analyzer.semanticHighlighter!!.highlight(f)
            assertEquals(listOf("fun" to HighlightKind.KEYWORD, "greet" to HighlightKind.FUNCTION), tokens.map { text.substring(it.range.start, it.range.end) to it.kind })
            assertEquals(setOf(HighlightModifier.DECLARATION), tokens[1].modifiers)

            val folds = analyzer.folding!!.folds(f)
            assertEquals(1, folds.size)
            assertEquals("\n  x\n", text.substring(folds[0].range.start, folds[0].range.end))

            val structure = analyzer.fileStructure(f, text)
            assertEquals(listOf("greet"), structure.map { it.name })
            assertEquals(SymbolKind.METHOD, structure[0].kind)
            assertEquals(4, structure[0].nameOffset)

            val hints = analyzer.inlayHints!!.hints(f, TextRange(0, text.length))
            assertEquals(": Unit", hints.single().parts.single().text)
            assertEquals(InlayHintKind.TYPE, hints.single().kind)

            val messy = "fun   greet() {}"
            parse(f, messy)
            val formatted = analyzer.formatting!!.format(f, messy, FormatStyle())
            assertEquals("fun greet() {}", LspConvert.applyEdits(messy, formatted))
        }
    }

    @Test
    fun anEditTheServerSendsOnItsOwnReachesTheHostAsFileEdits() {
        runBlocking {
            val f = file("own.toy", sample)
            support.diagnostics.diagnose(target(f, sample))
            val uri = uriOf(Path.of(f.path))
            val answer = server.connection!!.request("workspace/applyEdit", kotlinx.serialization.json.Json.parseToJsonElement(
                """{"edit":{"changes":{"$uri":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"newText":"good"}]}}}"""
            )).future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("""{"applied":true}""", answer.toString())
            val edits = applied.single().entries.single()
            assertEquals(Path.of(f.path), edits.key)
            assertEquals(sample.replace("bad", "good"), LspConvert.applyEdits(sample, edits.value))
        }
    }

    @Test
    fun configurationIsAnsweredFromTheDefinition() {
        runBlocking {
            val f = file("cfg.toy", "x")
            support.diagnostics.diagnose(target(f, "x"))
            val answer = server.connection!!.request("workspace/configuration", kotlinx.serialization.json.buildJsonObject {
                put("items", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("section", kotlinx.serialization.json.JsonPrimitive("toy")) })
                })
            }).future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("""[{"section":"toy"}]""", answer.toString())
        }
    }

    @Test
    fun aFileOutsideTheProjectHasNoServer() {
        runBlocking {
            val outside = Files.createTempFile("elsewhere", ".toy")
            try {
                val f = LspFile(outside)
                assertEquals(emptyList(), support.diagnostics.diagnose(target(f, "bad")))
                assertTrue(manager.sessions().isEmpty())
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun theSessionShutsDownWithItsProject() {
        runBlocking {
            val f = file("z.toy", sample)
            support.diagnostics.diagnose(target(f, sample))
            val session = manager.sessions().single()
            manager.closeProject(root)
            val deadline = System.currentTimeMillis() + 3_000
            while (session.state != LspSession.State.CLOSED && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(LspSession.State.CLOSED, session.state)
            // `exit` follows, but the fake tears its pipes down with the connection, so it may never read it.
            assertTrue("shutdown" in server.received, server.received.toString())
        }
    }

    @Test
    fun twoCallersAskingTheSameThingShareOneRequest() {
        val text = "fun greet() {}\nfun grow() {}\n"
        val path = file("s.toy", text).let { java.nio.file.Paths.get(it.path) }
        // Started and warm first, so both callers below reach the request at once.
        support.prefetch(LspLanguageSupport.Pass.FOLDS, path, text)
        server.delays["textDocument/documentSymbol"] = 300
        val asked = { server.received.count { it == "textDocument/documentSymbol" } }
        val before = asked()
        val callers = List(2) { Thread { support.prefetch(LspLanguageSupport.Pass.STRUCTURE, path, text) } }
        callers.forEach(Thread::start)
        callers.forEach { it.join(5_000) }
        assertEquals(1, asked() - before, server.received.toString())
        // And the answer they shared is what a later lookup gets, without asking again.
        server.delays.clear()
        val items = support.backend.createAnalyzer(object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = emptyList()
        }).fileStructure(LspFile(path), text)
        assertEquals(listOf("greet", "grow"), items.map { it.name })
        assertEquals(1, asked() - before)
    }

    @Test
    fun serverTiesAreBrokenByHowWellTheyMatchWhatWasTyped() {
        fun item(label: String, sort: String) = kotlinx.serialization.json.buildJsonObject {
            put("label", kotlinx.serialization.json.JsonPrimitive(label))
            put("sortText", kotlinx.serialization.json.JsonPrimitive(sort))
        }
        // Dart's answer for `Str`: one sortText for every type, in no useful order.
        val dart = listOf(item("StringBuffer", "9477"), item("StringSink", "9477"), item("Stream", "9477"), item("String", "9477"), item("strip", "9477"))
        // Shorter first among the case-sensitive matches, so String is no longer behind StringBuffer; the
        // case-insensitive `strip` comes last.
        assertEquals(
            listOf("Stream", "String", "StringSink", "StringBuffer", "strip"),
            LspLanguageSupport.completionOrder(dart, "Str").map { it.str("label") },
        )
        // An exact match beats everything the server ranked the same.
        assertEquals("String", LspLanguageSupport.completionOrder(dart, "String").first().str("label"))
        // The server's ranking still comes first, and with nothing typed its order is kept as it is.
        val ranked = listOf(item("StringBuffer", "1"), item("String", "2"))
        assertEquals(listOf("StringBuffer", "String"), LspLanguageSupport.completionOrder(ranked, "Str").map { it.str("label") })
        assertEquals(dart.map { it.str("label") }, LspLanguageSupport.completionOrder(dart, "").map { it.str("label") })
    }
}
