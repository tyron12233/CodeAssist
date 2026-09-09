package dev.ide.plugin.impl

import dev.ide.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PluginCatalog] gating: default-on, essential-locked, and dependency-aware disable (a disabled plugin's
 * transitive dependents drop so the load graph stays valid; essentials force their own dependencies on).
 */
class PluginCatalogTest {

    private fun m(id: String, essential: Boolean = false, deps: List<String> = emptyList()) =
        PluginManifest(id = id, name = id, essential = essential, dependsOn = deps)

    private val manifests = listOf(
        m("platform", essential = true),
        m("jdt", essential = true),
        m("kotlin", deps = listOf("jdt")),
        m("kotlin-analysis", deps = listOf("kotlin")),
        m("samples"),
    )

    @Test
    fun allEnabledByDefault() {
        val c = PluginCatalog(manifests, emptySet())
        assertEquals(manifests.map { it.id }.toSet(), c.enabledIds)
    }

    @Test
    fun disablingDropsTransitiveDependents() {
        val c = PluginCatalog(manifests, setOf("kotlin"))
        assertFalse(c.isEnabled("kotlin"))
        assertFalse(c.isEnabled("kotlin-analysis"), "a dependent of a disabled plugin is dropped")
        assertTrue(c.isEnabled("jdt"))
        assertTrue(c.isEnabled("samples"))
    }

    @Test
    fun essentialCannotBeDisabled() {
        val c = PluginCatalog(manifests, setOf("jdt", "platform"))
        assertTrue(c.isEnabled("jdt"))
        assertTrue(c.isEnabled("platform"))
        assertTrue(c.isEssential("jdt"))
        assertTrue(c.isEnabled("kotlin"), "kotlin depends on the forced-on essential jdt, so it stays enabled")
        assertEquals(emptySet(), c.disabledIds, "essential ids are ignored in the disabled set")
    }

    @Test
    fun essentialForcesItsDependencies() {
        val ms = listOf(m("core", essential = true, deps = listOf("base")), m("base"))
        val c = PluginCatalog(ms, setOf("base"))
        assertTrue(c.isEnabled("base"), "a dependency of an essential plugin is forced on")
        assertTrue(c.isEnabled("core"))
    }

    @Test
    fun independentDisableLeavesOthers() {
        val c = PluginCatalog(manifests, setOf("samples"))
        assertFalse(c.isEnabled("samples"))
        assertTrue(c.isEnabled("kotlin"))
        assertTrue(c.isEnabled("kotlin-analysis"))
    }

    @Test
    fun unknownDisabledIdIsIgnored() {
        val c = PluginCatalog(manifests, setOf("does-not-exist"))
        assertEquals(manifests.map { it.id }.toSet(), c.enabledIds)
        assertEquals(emptySet(), c.disabledIds)
    }
}
