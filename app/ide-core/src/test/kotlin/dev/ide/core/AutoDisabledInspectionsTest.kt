package dev.ide.core

import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.analysis.DiagnosticSink
import dev.ide.analysis.FileAnalyzer
import dev.ide.lang.LanguageId
import dev.ide.lang.dom.Severity
import dev.ide.model.template.TemplateArgs
import dev.ide.model.LanguageLevel
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An installed plugin's Java check that takes 60ms a pass, over the 25ms SYNTAX budget. */
class SlowJavaAnalyzerPlugin : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(ANALYZER_EP, SlowJavaAnalyzer)
    }
}

object SlowJavaAnalyzer : FileAnalyzer {
    val runs = AtomicInteger()
    override val id = AnalyzerId("com.example.slow.literals")
    override val displayName = "Slow literals"
    override val languages = setOf(LanguageId("java"))
    override val defaultSeverity = Severity.WARNING
    override val tier = AnalyzerTier.SYNTAX
    override val interestedIn = null
    override fun analyze(target: AnalysisTarget, sink: DiagnosticSink) {
        runs.incrementAndGet()
        Thread.sleep(60)
    }
}

private class SlowPluginSource : PluginSource {
    override val id = "fake"
    override fun discover(): List<PluginCandidate> = listOf(object : DiscoveredPlugin {
        override val manifest = PluginManifest(
            id = "com.example.slow", name = "Slow Plugin", apiVersion = PLUGIN_API_VERSION,
            entryPoints = listOf(SlowJavaAnalyzerPlugin::class.java.name), trusted = false,
        )
        override val origin = PluginOrigin("fake", "com.example.slow", signature = "cafe")
        override fun classLoader(): ClassLoader = javaClass.classLoader
    })
}

/**
 * An installed plugin's check that keeps running over its budget is switched off, and the project says so: the
 * list the editor notice and the Settings row read names the check and its plugin, and turning it back on puts it
 * to work again. Before, the only trace was a log line.
 */
class AutoDisabledInspectionsTest {

    @Test
    fun aSlowPluginCheckIsReportedAndCanBeTurnedBackOn() {
        withTempDir("auto-disabled") { root ->
            val env = ApplicationEnvironment(
                pluginSources = listOf(SlowPluginSource()),
                consentedPluginIds = setOf("com.example.slow"),
            )
            env.use {
                IdeServices.createProjectAt(
                    root, "java-console", mapOf(TemplateArgs.NAME to "Slow", TemplateArgs.PACKAGE to "com.acme"),
                    IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17, env = env,
                ).use { ide ->
                    val main = root.resolve("app/src/main/java/com/acme/Main.java")
                    val text = Files.readString(main)
                    SlowJavaAnalyzer.runs.set(0)

                    repeat(4) { runBlocking { ide.analyzeDiagnostics(main, text) } }
                    assertEquals(3, SlowJavaAnalyzer.runs.get(), "three slow passes, then it is off")

                    val off = ide.autoDisabledInspections.value.single()
                    assertEquals("com.example.slow.literals", off.id)
                    assertEquals("Slow literals", off.displayName)
                    assertEquals("Slow Plugin", off.pluginName, "named by the plugin's name, not its id")
                    assertTrue(off.slow && off.tookMs >= 60 && off.budgetMs == 25L, "$off")
                    assertEquals(1, off.times)

                    ide.releaseQuarantinedAnalyzer(AnalyzerId(off.id))
                    assertTrue(ide.autoDisabledInspections.value.isEmpty())
                    runBlocking { ide.analyzeDiagnostics(main, text) }
                    assertEquals(4, SlowJavaAnalyzer.runs.get(), "turned back on, it runs again")
                }
            }
        }
    }
}
