package dev.ide.core.plugins

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
        assertEquals(listOf("kotlin-language"), m.dependsOn)
        assertEquals(listOf("ui.toolWindow", "fs.read"), m.capabilities)
        assertEquals("3.11.0", m.minHostVersion)
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
        assertEquals(1, m.apiVersion)
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
        assertTrue("entryPoints" in (e.message ?: ""), e.message ?: "")
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
