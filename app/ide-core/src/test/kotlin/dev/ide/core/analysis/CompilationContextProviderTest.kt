package dev.ide.core.analysis

import dev.ide.core.IdeServices
import dev.ide.lang.COMPILATION_CONTEXT_PROVIDER_EP
import dev.ide.lang.CompilationContext
import dev.ide.lang.CompilationContextProvider
import dev.ide.lang.ContextKey
import dev.ide.lang.LanguageId
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.Workspace
import dev.ide.platform.PluginId
import dev.ide.testkit.withTempDir
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A plugin supplies the analysis inputs for its own language, which the host cannot derive from the project
 * model: the model can only describe a module as a classpath, a boot classpath and a Java language level, and
 * a virtualenv or an include path is none of those.
 */
class CompilationContextProviderTest {

    private class PyContext(private val interpreter: String) : CompilationContext {
        override val sourceRoots: List<VirtualFile> = emptyList()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> attribute(key: ContextKey<T>): T? =
            if (key === INTERPRETER) interpreter as T else null
    }

    private class Provider(
        override val languages: Set<LanguageId>,
        private val answer: (Module) -> CompilationContext?,
    ) : CompilationContextProvider {
        var asked = 0
        override fun contextFor(
            workspace: Workspace, module: Module, language: LanguageId, variant: Set<String>?,
        ): CompilationContext? {
            asked++
            return answer(module)
        }
    }

    private fun <T> withEngine(block: (IdeServices, Workspace, Module) -> T): T =
        withTempDir("compilation-context-ep") { dir ->
            IdeServices.bootstrapJavaDemo(dir).use { ide ->
                block(ide, ide.store.workspace, ide.modules().first())
            }
        }

    private fun resolve(
        providers: List<CompilationContextProvider>,
        workspace: Workspace,
        module: Module,
        language: LanguageId,
        onError: (CompilationContextProvider, Throwable) -> Unit = { _, _ -> },
    ): CompilationContext = CompilationContexts.resolve(
        providers, workspace, module, language, variant = null, onError = onError,
    ) { HOST_FALLBACK }

    @Test
    fun aProviderForTheLanguageSuppliesTheContext() {
        withEngine { _, ws, module ->
            val provider = Provider(setOf(MYLANG)) { PyContext("python3.12") }
            val ctx = resolve(listOf(provider), ws, module, MYLANG)

            assertEquals("python3.12", ctx.attribute(INTERPRETER), "the plugin's own input reaches its backend")
            assertEquals(1, provider.asked)
            // A language with no classpath does not have to fabricate one.
            assertSame(ClasspathSnapshot.EMPTY, ctx.classpath)
            assertSame(ClasspathSnapshot.EMPTY, ctx.bootClasspath)
            assertEquals(LanguageLevel.DEFAULT, ctx.languageLevel)
            assertNull(ctx.outputDir, "a language that compiles to nothing has no output dir")
            assertTrue(ctx.processors.isEmpty())
        }
    }

    @Test
    fun aProviderIsNotAskedAboutALanguageItDoesNotClaim() {
        withEngine { _, ws, module ->
            val provider = Provider(setOf(MYLANG)) { PyContext("python3.12") }
            assertSame(HOST_FALLBACK, resolve(listOf(provider), ws, module, JAVA))
            assertEquals(0, provider.asked, "Java analysis never consults a provider that claims only mylang")
        }
    }

    @Test
    fun aProviderThatDeclinesFallsBackToTheHostContext() {
        withEngine { _, ws, module ->
            val declines = Provider(setOf(MYLANG)) { null }
            val answers = Provider(setOf(MYLANG)) { PyContext("pypy") }

            assertSame(HOST_FALLBACK, resolve(listOf(declines), ws, module, MYLANG))
            assertEquals(1, declines.asked)

            // First non-null wins.
            val ctx = resolve(listOf(declines, answers), ws, module, MYLANG)
            assertEquals("pypy", ctx.attribute(INTERPRETER))
        }
    }

    @Test
    fun aBrokenProviderIsReportedAndSkippedRatherThanBreakingAnalysis() {
        withEngine { _, ws, module ->
            val broken = Provider(setOf(MYLANG)) { error("provider blew up") }
            val healthy = Provider(setOf(MYLANG)) { PyContext("python3.12") }
            val failures = ArrayList<Throwable>()

            val ctx = resolve(listOf(broken, healthy), ws, module, MYLANG) { _, t -> failures.add(t) }

            assertEquals("python3.12", ctx.attribute(INTERPRETER), "the next provider still gets its turn")
            assertEquals(1, failures.size)
            assertEquals("provider blew up", failures.single().message)

            // With nothing else to try, the host's own context is used.
            assertSame(HOST_FALLBACK, resolve(listOf(broken), ws, module, MYLANG))
        }
    }

    @Test
    fun withNoProvidersTheHostContextIsUsedUnchanged() {
        withEngine { _, ws, module ->
            assertSame(HOST_FALLBACK, resolve(emptyList(), ws, module, MYLANG))
        }
    }

    @Test
    fun theExtensionPointIsWhereAPluginContributesOne() {
        withEngine { ide, _, _ ->
            val provider = Provider(setOf(MYLANG)) { PyContext("python3.12") }
            ide.platform.extensions.register(COMPILATION_CONTEXT_PROVIDER_EP, provider, PluginId("mylang-support"))

            val registered = ide.platform.extensions.extensions(COMPILATION_CONTEXT_PROVIDER_EP)
            assertTrue(provider in registered, "the host reads providers off the EP on every lookup")
        }
    }

    private companion object {
        val MYLANG = LanguageId("mylang")
        val JAVA = LanguageId("java")

        /** The plugin's own input, which the core has no name for. Read back by the same plugin's backend. */
        val INTERPRETER = ContextKey<String>("mylang.interpreter")

        /** Stands in for the model-derived context the host builds when no provider answers. */
        val HOST_FALLBACK: CompilationContext = object : CompilationContext {
            override val sourceRoots: List<VirtualFile> = emptyList()
        }
    }
}
