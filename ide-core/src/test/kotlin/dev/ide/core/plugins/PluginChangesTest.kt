package dev.ide.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Plugins screen is told is waiting for a restart. The rule under test throughout: a change is
 * listed only when a restart would load something different from what is loaded now.
 */
class PluginChangesTest {

    private val hello = KnownPlugin(packageName = "com.example.hello", id = "com.example.hello", name = "Hello Plugin")

    private fun changes(
        installed: List<KnownPlugin> = listOf(hello),
        disabled: Set<String> = emptySet(),
        consented: Set<String> = setOf(hello.id),
    ) = PluginChanges(installedAtStart = installed, disabledAtStart = disabled, consentedAtStart = consented)

    @Test
    fun `a freshly started process has nothing waiting`() {
        assertTrue(changes().pending().isEmpty())
    }

    @Test
    fun `a plugin app that was not installed at startup reads as installed`() {
        val c = changes(installed = emptyList(), consented = emptySet())
        c.packageInstalled("com.example.other", "Other Plugin")
        assertEquals(
            listOf(PluginChange("com.example.other", "Other Plugin", PluginChangeKind.INSTALLED)),
            c.pending(),
        )
    }

    @Test
    fun `installing over a plugin the process loaded reads as updated`() {
        val c = changes()
        c.packageInstalled(hello.packageName, hello.name)
        assertEquals(listOf(PluginChange(hello.id, hello.name, PluginChangeKind.UPDATED)), c.pending())
    }

    @Test
    fun `a second install of the same package does not stack`() {
        val c = changes()
        c.packageInstalled(hello.packageName, hello.name)
        c.packageInstalled(hello.packageName, hello.name)
        assertEquals(1, c.pending().size)
    }

    @Test
    fun `uninstalling a plugin the process loaded reads as uninstalled, under the name it loaded with`() {
        val c = changes()
        // The package is gone by the time the host hears about it, so no name comes with the event.
        c.packageRemoved(hello.packageName)
        assertEquals(listOf(PluginChange(hello.id, hello.name, PluginChangeKind.UNINSTALLED)), c.pending())
    }

    @Test
    fun `a plugin installed and uninstalled inside one session leaves nothing to apply`() {
        val c = changes(installed = emptyList(), consented = emptySet())
        c.packageInstalled("com.example.other", "Other Plugin")
        c.packageRemoved("com.example.other")
        assertTrue(c.pending().isEmpty())
    }

    @Test
    fun `uninstalling a package the process never had is not a change`() {
        val c = changes()
        c.packageRemoved("com.example.unrelated")
        assertTrue(c.pending().isEmpty())
    }

    @Test
    fun `turning a built-in off is waiting, and turning it back on clears it`() {
        val c = changes()
        c.choicesChanged(disabled = setOf("kotlin-language"), consented = setOf(hello.id))
        assertEquals(
            listOf(PluginChange("kotlin-language", "Kotlin", PluginChangeKind.DISABLED)),
            c.pending { id -> "Kotlin".takeIf { id == "kotlin-language" } },
        )

        c.choicesChanged(disabled = emptySet(), consented = setOf(hello.id))
        assertTrue(c.pending().isEmpty())
    }

    @Test
    fun `allowing an installed plugin to run is waiting`() {
        val c = changes(consented = emptySet())
        c.choicesChanged(disabled = emptySet(), consented = setOf(hello.id))
        assertEquals(listOf(PluginChange(hello.id, hello.name, PluginChangeKind.ENABLED)), c.pending())
    }

    @Test
    fun `refusing a plugin that was never allowed to run changes nothing`() {
        // A refusal is persisted as a disable, but the plugin was not running either way, so there is
        // nothing for a restart to apply.
        val c = changes(consented = emptySet())
        c.choicesChanged(disabled = setOf(hello.id), consented = emptySet())
        assertTrue(c.pending().isEmpty())
    }

    @Test
    fun `a package event stands for its plugin, so a decision about it is not listed twice`() {
        val c = changes()
        c.packageInstalled(hello.packageName, hello.name)
        c.choicesChanged(disabled = setOf(hello.id), consented = setOf(hello.id))
        assertEquals(listOf(PluginChange(hello.id, hello.name, PluginChangeKind.UPDATED)), c.pending())
    }

    @Test
    fun `package events come before decisions`() {
        val c = changes()
        c.choicesChanged(disabled = setOf("kotlin-language"), consented = setOf(hello.id))
        c.packageInstalled(hello.packageName, hello.name)
        assertEquals(
            listOf(PluginChangeKind.UPDATED, PluginChangeKind.DISABLED),
            c.pending().map { it.kind },
        )
    }

    @Test
    fun `a plugin whose manifest could not be read is still named when it is uninstalled`() {
        val unreadable = KnownPlugin(packageName = "com.example.broken", id = "", name = "Broken Plugin")
        val c = changes(installed = listOf(unreadable), consented = emptySet())
        c.packageRemoved(unreadable.packageName)
        assertEquals(
            listOf(PluginChange("com.example.broken", "Broken Plugin", PluginChangeKind.UNINSTALLED)),
            c.pending(),
        )
    }

    @Test
    fun `an id with no catalog name falls back to the id`() {
        val c = changes()
        c.choicesChanged(disabled = setOf("some-plugin"), consented = setOf(hello.id))
        assertEquals(listOf("some-plugin"), c.pending().map { it.name })
    }
}
