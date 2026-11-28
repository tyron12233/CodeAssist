package dev.ide.core

import dev.ide.core.plugins.BUILT_IN_PLUGIN_DOCS
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Plugins screen shows a page for each built-in plugin, written for the person using the IDE. A built-in without
 * one would fall back to the developer's one-line manifest description ("Core file-icon classifier and base file-type
 * mappings"), which says nothing to anyone who did not write it.
 */
class BuiltInPluginDocsTest {

    private val env = ApplicationEnvironment()

    @AfterTest
    fun tearDown() {
        env.close()
    }

    @Test
    fun `every built-in plugin has a page, and every page is for a plugin that exists`() {
        val ids = env.pluginCatalog.all.map { it.id }.toSet()
        val missing = ids - BUILT_IN_PLUGIN_DOCS.keys
        val stale = BUILT_IN_PLUGIN_DOCS.keys - ids
        assertTrue(missing.isEmpty(), "built-in plugins with no entry in BUILT_IN_PLUGIN_DOCS: $missing")
        assertTrue(stale.isEmpty(), "BUILT_IN_PLUGIN_DOCS entries for plugins that are not built in: $stale")
    }

    @Test
    fun `every page says what the plugin is, what it adds and what turning it off means`() {
        for ((id, d) in BUILT_IN_PLUGIN_DOCS) {
            assertTrue(d.summary.isNotBlank() && d.summary.length <= 130, "$id: a one-sentence summary (<= 130 characters)")
            assertTrue(d.about.length >= 60, "$id: a paragraph about what it is for")
            assertTrue(d.adds.isNotEmpty() && d.adds.all { it.isNotBlank() }, "$id: what it adds")
            assertTrue(d.ifOff.length >= 20, "$id: what turning it off means (or why it cannot be turned off)")
        }
    }
}
