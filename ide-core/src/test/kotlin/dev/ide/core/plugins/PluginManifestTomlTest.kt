package dev.ide.core.plugins

import dev.ide.plugin.PLUGIN_API_VERSION
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManifestTomlTest {

    @Test
    fun `reads every field from a plugin table`() {
        val m = PluginManifestToml.parse(
            """
            [plugin]
            id = "com.example.hello"
            name = "Hello"
            version = "1.2.0"
            apiVersion = 1
            description = "Adds a Hello tool window."
            entryPoints = ["com.example.hello.HelloPlugin"]
            uiEntryPoints = ["com.example.hello.HelloUiPlugin"]
            dependsOn = ["kotlin-language"]
            capabilities = ["ui.toolWindow", "fs.read"]
            minHostVersion = "3.11.0"
            """.trimIndent()
        )
        assertEquals("com.example.hello", m.id)
        assertEquals("Hello", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals(1, m.apiVersion)
        assertEquals("Adds a Hello tool window.", m.description)
        assertEquals(listOf("com.example.hello.HelloPlugin"), m.entryPoints)
        assertEquals(listOf("com.example.hello.HelloUiPlugin"), m.uiEntryPoints)
        assertEquals(listOf("kotlin-language"), m.dependsOn)
        assertEquals(listOf("ui.toolWindow", "fs.read"), m.capabilities)
        assertEquals("3.11.0", m.minHostVersion)
    }

    /**
     * The sample plugin's real manifest, read from the tree. The sample is a module of this build so it cannot
     * drift from an SPI change, but a compile cannot check the manifest: the entry-point names in it are
     * strings, and a typo there is a plugin that installs and does nothing. This is the check the loader makes,
     * run against the file that ships.
     */
    @Test
    fun `the sample plugin's manifest names classes the sample actually has`() {
        val sample = java.nio.file.Paths.get("../samples/hello-plugin/src/main")
        if (!java.nio.file.Files.isDirectory(sample)) return // not this checkout's problem
        val m = PluginManifestToml.parse(sample.resolve("res/raw/codeassist_plugin.toml").readText())

        assertEquals("com.example.hello", m.id)
        for (fqcn in m.entryPoints + m.uiEntryPoints) {
            val source = sample.resolve("kotlin/${fqcn.replace('.', '/')}.kt")
            assertTrue(java.nio.file.Files.isRegularFile(source), "$fqcn is named by the manifest but has no source")
            val simpleName = fqcn.substringAfterLast('.')
            val supertype = if (fqcn in m.uiEntryPoints) "UiPlugin" else "Plugin"
            assertTrue(
                "class $simpleName : $supertype" in source.readText(),
                "$fqcn must implement $supertype for the loader to cast it",
            )
        }
    }

    @Test
    fun `accepts bare top-level keys`() {
        val m = PluginManifestToml.parse(
            """
            id = "com.example.flat"
            entryPoints = ["com.example.FlatPlugin"]
            """.trimIndent()
        )
        assertEquals("com.example.flat", m.id)
        // Name falls back to the id, and the remaining fields to their defaults.
        assertEquals("com.example.flat", m.name)
        assertEquals("1.0.0", m.version)
        // A manifest that names no apiVersion is read as the one this IDE loads, so a hand-written manifest
        // is current by omission rather than pinned to whatever the field defaulted to when it was written.
        assertEquals(PLUGIN_API_VERSION, m.apiVersion)
        assertTrue(m.dependsOn.isEmpty())
        assertNull(m.minHostVersion)
    }

    @Test
    fun `the host owns essential and trusted whatever the file claims`() {
        val m = PluginManifestToml.parse(
            """
            id = "com.example.sneaky"
            entryPoints = ["com.example.SneakyPlugin"]
            essential = true
            trusted = true
            """.trimIndent()
        )
        assertTrue(!m.essential, "an installed plugin cannot make itself undisablable")
        assertTrue(!m.trusted, "trust follows from the origin's signature, not a self-declaration")
    }

    @Test
    fun `rejects a manifest with no id`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PluginManifestToml.parse("""entryPoints = ["com.example.P"]""")
        }
        assertTrue("id" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `rejects a manifest with no entry point`() {
        val e = assertFailsWith<IllegalArgumentException> {
            PluginManifestToml.parse("""id = "com.example.p"""")
        }
        // The message has to name both lists, since either one alone would have been enough.
        assertTrue("entryPoints" in (e.message ?: ""), e.message ?: "")
        assertTrue("uiEntryPoints" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `a UI facet alone is a complete manifest`() {
        // A plugin contributing only Compose UI has no engine entry point, and that is not a mistake.
        val m = PluginManifestToml.parse(
            """
            id = "com.example.panel"
            uiEntryPoints = ["com.example.panel.PanelUiPlugin"]
            """.trimIndent()
        )
        assertEquals(emptyList(), m.entryPoints)
        assertEquals(listOf("com.example.panel.PanelUiPlugin"), m.uiEntryPoints)
    }

    @Test
    fun `accepts the capitals an applicationId or a Java package would carry`() {
        val m = PluginManifestToml.parse(
            """
            id = "kz.codingOnTheMoon.mypreciousplugin"
            entryPoints = ["kz.codingOnTheMoon.mypreciousplugin.MyPreciousPlugIn"]
            """.trimIndent()
        )
        // The id keeps the case it was written in: it is the identity, not a display name.
        assertEquals("kz.codingOnTheMoon.mypreciousplugin", m.id)
    }

    @Test
    fun `rejects an id that is not a plain identifier`() {
        assertFailsWith<IllegalArgumentException> {
            PluginManifestToml.parse(
                """
                id = "Com Example/Hello"
                entryPoints = ["com.example.P"]
                """.trimIndent()
            )
        }
    }

    @Test
    fun `rejects text that is not TOML`() {
        val e = assertFailsWith<IllegalArgumentException> { PluginManifestToml.parse("{\"id\": \"x\"}") }
        assertTrue("TOML" in (e.message ?: ""), e.message ?: "")
    }
}
