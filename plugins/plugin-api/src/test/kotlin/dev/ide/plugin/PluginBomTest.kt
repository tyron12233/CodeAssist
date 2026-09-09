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
        val modules = moduleDirs(root)
        val published = modules
            .filter { "dev.ide.spi-publish" in File(it, "build.gradle.kts").readText() }
            .map { it.name }
            .sorted()
        assertTrue(published.isNotEmpty(), "found no published modules under $root; has the layout changed?")

        val bom = modules.singleOrNull { it.name == "plugin-bom" }?.let { File(it, "build.gradle.kts") }
        assertTrue(bom != null && bom.isFile, "the BOM is missing under $root")
        val text = bom.readText()
        for (module in published) {
            assertTrue(
                """project(":$module")""" in text,
                ":$module is published but the BOM carries no version for it",
            )
        }
    }

    /**
     * Every Gradle module in the checkout, found rather than listed.
     *
     * Modules are grouped into layer directories (`plugins/plugin-api`, `app/ide-ui`, ...), so they are one
     * level below the root -- except `samples/`, which nests one deeper. Searching two levels down covers
     * both and does not need updating when a module changes layer, which is the whole point of the Gradle
     * paths being independent of the directories.
     */
    private fun moduleDirs(root: File): List<File> {
        val depthOne = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        val depthTwo = depthOne.flatMap { it.listFiles().orEmpty().filter(File::isDirectory) }
        return (depthOne + depthTwo).filter { File(it, "build.gradle.kts").isFile }
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
