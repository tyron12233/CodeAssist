package dev.ide.core.project

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectArchivesTest {

    private val dir = createTempDirectory("project-archives")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /** A folder with a settings script is a Gradle project; nothing else is anything. */
    private val inspect: (Path) -> ImportableKind = { d ->
        if (Files.exists(d.resolve("settings.gradle.kts")) || Files.exists(d.resolve("settings.gradle"))) ImportableKind.EXTERNAL
        else ImportableKind.NONE
    }

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = dir.resolve("in-${System.nanoTime()}.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun extract(archive: Path) = ProjectArchives.extract(archive, dir.resolve("out-${System.nanoTime()}"), inspect)

    @Test
    fun aDownloadsWrapperFolderIsLookedThrough() {
        val contents = extract(zip(
            "MyApp-main/settings.gradle.kts" to "rootProject.name = \"MyApp\"",
            "MyApp-main/app/build.gradle.kts" to "plugins {}",
            "MyApp-main/app/src/main/java/A.java" to "class A {}",
        ))!!
        assertEquals(listOf("MyApp-main"), contents.projects.map { it.relativePath })
        assertEquals(ImportableKind.EXTERNAL, contents.projects[0].kind)
        assertTrue(contents.projects[0].path.resolve("app/src/main/java/A.java").exists())
    }

    @Test
    fun aSamplesRepositoryListsEachProjectButNotTheirModules() {
        val contents = extract(zip(
            "samples-main/README.md" to "",
            "samples-main/Jetchat/settings.gradle.kts" to "",
            "samples-main/Jetchat/app/settings.gradle.kts" to "",
            "samples-main/Jetsnack/settings.gradle" to "",
        ))!!
        assertEquals(listOf("samples-main/Jetchat", "samples-main/Jetsnack"), contents.projects.map { it.relativePath })
    }

    @Test
    fun buildOutputsPackagesAndMacMetadataAreLeftBehind() {
        val contents = extract(zip(
            "p/settings.gradle" to "",
            "p/app/build/outputs/apk/debug/app-debug.apk" to "x",
            "p/.gradle/8.0/cache.bin" to "x",
            "p/app/release/app.aab" to "x",
            "p/libs/lib.jar" to "x",
            "__MACOSX/p/._settings.gradle" to "x",
            "p/.DS_Store" to "x",
        ))!!
        val root = contents.root
        assertFalse(root.resolve("p/app/build").exists())
        assertFalse(root.resolve("p/.gradle").exists())
        assertFalse(root.resolve("p/app/release/app.aab").exists())
        assertFalse(root.resolve("__MACOSX").exists())
        assertFalse(root.resolve("p/.DS_Store").exists())
        assertTrue(root.resolve("p/libs/lib.jar").exists(), "a local library jar is part of the project")
    }

    @Test
    fun anEntryEscapingTheFolderFailsTheArchive() {
        assertFailsWith<IOException> { extract(zip("p/settings.gradle" to "", "../evil.txt" to "x")) }
        assertFalse(dir.resolve("evil.txt").exists())
    }

    @Test
    fun notAZipIsNull() {
        val file = dir.resolve("plain.zip").also { Files.writeString(it, "not a zip") }
        assertNull(extract(file))
    }

    @Test
    fun aZipWithNoProjectFindsNothing() {
        assertEquals(emptyList(), extract(zip("notes/readme.txt" to "hi"))!!.projects)
    }
}
