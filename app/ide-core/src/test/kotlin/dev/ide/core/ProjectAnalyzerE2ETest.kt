package dev.ide.core

import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.ProjectAnalysisScope
import dev.ide.analysis.ProjectAnalyzer
import dev.ide.analysis.ProjectDiagnosticSink
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.PluginId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plugin's [ProjectAnalyzer] runs from the editor alone.
 *
 * The editor asks for diagnostics one file at a time, and that per-file pass never reaches the PROJECT tier,
 * so a project analyzer used to be registered, listed, and never called. The editor backend now follows a
 * settled pass with a project sweep, and the sweep's scope lists Kotlin sources as well as Java ones.
 */
class ProjectAnalyzerE2ETest {

    private val root = createTempDirectory("project-analyzer-e2e")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** Reports once on every Kotlin file the sweep lists, and records what it was shown. */
    private class MarksKotlinFiles : ProjectAnalyzer {
        override val id = AnalyzerId("test.marksKotlin")
        override val displayName = "Marks Kotlin files"
        override val languages = setOf(LanguageId("kotlin"))
        override val defaultSeverity = Severity.INFO
        override val tier = AnalyzerTier.PROJECT
        val seen = CopyOnWriteArrayList<String>()

        override suspend fun analyze(scope: ProjectAnalysisScope, sink: ProjectDiagnosticSink) {
            for (file in scope.files()) {
                seen += file.path
                if (file.path.endsWith(".kt")) sink.report(file, TextRange(0, 1), defaultSeverity, "project finding")
            }
        }
    }

    @Test
    fun aPluginProjectAnalyzerRunsAfterAnEditorPassAndItsFindingsReachTheEditor() {
        val analyzer = MarksKotlinFiles()
        val env = ApplicationEnvironment()
        env.platform.extensions.register(ANALYZER_EP, analyzer, PluginId("test-project-analyzer"))
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }
        val backend = IdeServicesBackend(s)

        val text = "package com.example.app\n\nclass Graph\n"
        val file = write(s, "Graph.kt", text)

        runBlocking {
            // Subscribed before the pass: the flow keeps no replay, and the sweep may finish at any point after it.
            val invalidated = async(start = CoroutineStart.UNDISPATCHED) { backend.editor.diagnosticsInvalidated.first() }
            backend.editor.analyze(file.toString(), text)
            val paths = withTimeout(30_000) { invalidated.await() }

            assertEquals(setOf(file.toString()), paths, "the sweep names the open file whose findings changed")
            assertTrue(analyzer.seen.any { it.endsWith("/Graph.kt") }, "the sweep lists Kotlin sources: ${analyzer.seen}")

            val shown = backend.editor.analyze(file.toString(), text).map { it.message }
            assertTrue("project finding" in shown, "the next editor pass shows the project finding, got $shown")
        }
    }

    private fun write(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        services.modules()
        return file
    }
}
