package dev.codeassist.csharp.project

import dev.codeassist.csharp.newTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CsRunConfigTest {

    private val root: Path = newTempDir()

    private fun settings(text: String, at: Path = root) {
        val file = at.resolve(".platform/settings.properties")
        Files.createDirectories(file.parent)
        file.toFile().writeText(text)
    }

    private fun module(path: String): Path = root.resolve(path).also { Files.createDirectories(it) }

    @Test
    fun `the class set for the module is read`() {
        settings("module.mainClass.app=Tools.Cli\nother=1\n")
        assertEquals("Tools.Cli", CsRunConfig.mainClassOverride("app", module("app")))
    }

    @Test
    fun `a blank value is not set`() {
        settings("module.mainClass.app=   \n")
        assertNull(CsRunConfig.mainClassOverride("app", module("app")))
    }

    @Test
    fun `another module's setting is not this module's`() {
        settings("module.mainClass.other=X\n")
        assertNull(CsRunConfig.mainClassOverride("app", module("app")))
    }

    @Test
    fun `no settings file means not set`() {
        assertNull(CsRunConfig.mainClassOverride("app", module("app")))
    }

    @Test
    fun `the project folder is found above the module`() {
        settings("module.mainClass.app = N.Program\n")
        assertEquals("N.Program", CsRunConfig.mainClassOverride("app", module("src/app")))
    }

    @Test
    fun `a value with a trailing space and a key written with a colon`() {
        settings("module.mainClass.app: N.Program \n")
        assertEquals("N.Program", CsRunConfig.mainClassOverride("app", module("app")))
    }
}
