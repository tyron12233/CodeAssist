package dev.codeassist.csharp.sdk

import dev.codeassist.csharp.newTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RuntimeConfigTest {

    private val root: Path = newTempDir()

    @Test
    fun `a missing file is written`() {
        val file = root.resolve("App.runtimeconfig.json")
        assertTrue(ensureRuntimeConfig(file))
        assertEquals(RUNTIME_CONFIG, String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    @Test
    fun `a stale file from an earlier runtime is replaced`() {
        val file = root.resolve("App.runtimeconfig.json")
        Files.write(file, """{"runtimeOptions":{"framework":{"version":"9.0.0"}}}""".toByteArray())
        assertTrue(ensureRuntimeConfig(file))
        assertEquals(RUNTIME_CONFIG, String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    @Test
    fun `a current file is left alone`() {
        val file = root.resolve("App.runtimeconfig.json")
        Files.write(file, RUNTIME_CONFIG.toByteArray(Charsets.UTF_8))
        val before = Files.getLastModifiedTime(file)
        assertFalse(ensureRuntimeConfig(file))
        assertEquals(before, Files.getLastModifiedTime(file))
    }

    @Test
    fun `the config names net10`() {
        assertTrue(RUNTIME_CONFIG.contains("\"tfm\":\"net10.0\""))
        assertTrue(RUNTIME_CONFIG.contains("Microsoft.NETCore.App"))
    }
}
