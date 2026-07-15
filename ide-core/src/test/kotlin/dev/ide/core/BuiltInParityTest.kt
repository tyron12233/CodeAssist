package dev.ide.core

import dev.ide.index.INDEX_EP
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.LanguageId
import dev.ide.lang.jdt.JdtLanguageBackend
import dev.ide.lang.kotlin.compile.ComposeCompilerPlugin
import dev.ide.lang.kotlin.compile.KOTLIN_COMPILER_PLUGIN_EP
import dev.ide.lang.synthetic.SYNTHETIC_CLASS_EP
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceScopeLevel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity guard for the Phase-1 dogfood inversion: the built-in [dev.ide.plugin.Plugin]s loaded by
 * [ApplicationEnvironment] must produce the same app-global registry the old imperative
 * `registerStaticPlugins`/`registerEngineServices` block did. Asserts the load-order invariant (JDT first),
 * the extension counts, the scoped-service keys/levels, and the hand-threaded facet-codec injection.
 */
class BuiltInParityTest {

    private val env = ApplicationEnvironment()
    private val ext get() = env.platform.extensions

    @AfterTest
    fun tearDown() {
        env.close()
    }

    @Test
    fun `JDT language backend loads first so it is the backendFor fallback`() {
        val backends = ext.extensions(LANGUAGE_BACKEND_EP)
        assertEquals(3, backends.size, "JDT + XML + Kotlin backends")
        assertTrue(backends.first() is JdtLanguageBackend, "JDT must resolve first (the fallback)")
    }

    @Test
    fun `all built-in index extensions are registered`() {
        assertEquals(24, ext.extensions(INDEX_EP).size)
    }

    @Test
    fun `synthetic-class providers are registered (BuildConfig, ViewBinding, Kotlin, R)`() {
        assertEquals(4, ext.extensions(SYNTHETIC_CLASS_EP).size)
    }

    @Test
    fun `engine scoped services are registered at the expected scopes`() {
        val byId = ext.extensions(SERVICE_EP).associateBy { it.key.id }
        val moduleAnalyzers = setOf("ide.analyzer.java", "ide.analyzer.kotlin", "ide.analyzer.xml")
        val workspaceServices = setOf(
            "ide.service.signing", "ide.service.search", "ide.service.blocks", "ide.service.actions",
            "ide.service.dependencies", "ide.service.modules", "ide.service.build",
            "ide.service.languageFeatures", "ide.service.androidResources", "ide.service.refactor",
            "ide.service.kotlinEditor", "ide.service.composePreview",
        )
        assertEquals(moduleAnalyzers + workspaceServices, byId.keys, "exactly the 15 engine services")
        moduleAnalyzers.forEach { assertEquals(ServiceScopeLevel.MODULE, byId.getValue(it).level, it) }
        workspaceServices.forEach { assertEquals(ServiceScopeLevel.WORKSPACE, byId.getValue(it).level, it) }
    }

    @Test
    fun `the AndroidFacet codec is registered on the env-owned FacetCodecRegistry`() {
        assertNotNull(env.codecs.codecForTable("android"), "android-support plugin must register its facet codec")
    }

    @Test
    fun `file-type mappings route built-in extensions incl the backend-less types`() {
        val mappings = ext.extensions(FILE_TYPE_EP).sortedBy { it.order }
        assertEquals(5, mappings.size, "java, xml, kotlin, proguard, markdown")
        fun langOf(name: String): LanguageId? = mappings.firstOrNull { it.matches(name) }?.language
        // The backend-less types must resolve off Java so the diagnostics engine never analyses them as Java.
        assertEquals(LanguageId("proguard"), langOf("rules.pro"))
        assertEquals(LanguageId("markdown"), langOf("README.md"))
        assertEquals(LanguageId("java"), langOf("Main.java"))
        assertNull(langOf("Makefile"), "an unmapped file falls through to the host's Java default")
    }

    @Test
    fun `the Compose kotlin-compiler plugin is contributed on the EP by a built-in plugin`() {
        assertTrue(
            ext.extensions(KOTLIN_COMPILER_PLUGIN_EP).contains(ComposeCompilerPlugin),
            "kotlin-support must contribute Compose so BuildService (a pure consumer) reads it off the EP",
        )
    }
}
