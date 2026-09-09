package dev.ide.plugin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The BOM has to name every artifact this build publishes.
 *
 * The two lists live in different files by necessity: a module opts into publication by applying
 * `dev.ide.spi-publish`, and `:plugin-bom` names it in a constraint. A tenth SPI module added without the
 * matching constraint would publish fine and break only for the plugin author who took their versions from
 * the BOM and asked for the one artifact it does not carry a version for.
 */
class PluginBomTest {

    @Test
    fun `the BOM constrains every module this build publishes`() {
        val root = repoRoot()
        val published = root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .filter { module ->
                val buildFile = File(module, "build.gradle.kts")
                buildFile.isFile && "dev.ide.spi-publish" in buildFile.readText()
            }
            .map { it.name }
            .sorted()
        assertTrue(published.isNotEmpty(), "found no published modules under $root; has the layout changed?")

        val bom = File(root, "plugin-bom/build.gradle.kts")
        assertTrue(bom.isFile, "the BOM is missing: $bom")
        val text = bom.readText()
        for (module in published) {
            assertTrue(
                """project(":$module")""" in text,
                ":$module is published but the BOM carries no version for it",
            )
        }
    }

    /** The checkout root, from whichever directory the test worker was started in. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }
}
