package dev.ide.core

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.Diagnostic
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.DiagnosticProvider
import dev.ide.analysis.DiagnosticSource
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.platform.PluginId
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A language whose diagnostics come only from a [DiagnosticProvider] is analysed.
 *
 * The host will not build an analysis target for a file whose language has neither a `LanguageBackend` nor
 * anything claiming it, which is what keeps a `.txt` inert. That gate used to read only the analyzers, so a
 * language served by a provider alone was silently never analysed and its provider never ran.
 *
 * It is not a hypothetical shape: a provider is the only one of the two that may **suspend**, so it is what a
 * language whose compiler is an external process has to use. The NDK plugin's C and C++ support is exactly
 * that, and this is the gate it needs open.
 */
class ProviderOnlyLanguageTest {

    private val root = createTempDirectory("provider-only-language")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** Claims a made-up language and always reports one problem, so "did it run" is unambiguous. */
    private class AlwaysComplains(private val language: String) : DiagnosticProvider {
        override val id = "test.alwaysComplains"
        override val languages = setOf(LanguageId(language))

        override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> = listOf(
            Diagnostic(
                range = TextRange(0, 1),
                severity = Severity.ERROR,
                message = "reported by a provider-only language",
                source = DiagnosticSource.Compiler,
            )
        )
    }

    @Test
    fun aLanguageClaimedOnlyByADiagnosticProviderIsAnalysed() {
        val env = ApplicationEnvironment()
        env.platform.extensions.register(
            FILE_TYPE_EP, FileTypeMapping(listOf(".widget"), LanguageId("widget")), PluginId("test-widget"),
        )
        env.platform.extensions.register(
            DIAGNOSTIC_PROVIDER_EP, AlwaysComplains("widget"), PluginId("test-widget"),
        )
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }

        val text = "anything at all\n"
        val file = write(s, "thing.widget", text)

        val diagnostics = runBlocking { s.analyzeDiagnostics(file, text) }
        assertEquals(
            listOf("reported by a provider-only language"),
            diagnostics.map { it.message },
            "the provider's language must open the analysis gate on its own",
        )
    }

    /**
     * And the gate still holds for everything else. A provider that names NO language applies to every
     * language, which must not be read as a reason to start analysing plain text.
     */
    @Test
    fun aProviderThatNamesNoLanguageDoesNotMakePlainTextAnalysable() {
        val env = ApplicationEnvironment()
        env.platform.extensions.register(
            DIAGNOSTIC_PROVIDER_EP,
            object : DiagnosticProvider {
                override val id = "test.everywhere"
                override suspend fun diagnose(target: AnalysisTarget): List<Diagnostic> = listOf(
                    Diagnostic(TextRange(0, 1), Severity.ERROR, "should not be reached", DiagnosticSource.Compiler)
                )
            },
            PluginId("test-everywhere"),
        )
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }

        val text = "just some notes\n"
        val file = write(s, "notes.txt", text)

        assertTrue(
            runBlocking { s.analyzeDiagnostics(file, text) }.isEmpty(),
            "a .txt has no language of its own and must stay inert",
        )
    }

    private fun write(services: IdeServices, name: String, text: String): Path {
        val file = root.resolve("app/src/main/java/com/example/app/$name")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        services.modules()
        return file
    }
}
