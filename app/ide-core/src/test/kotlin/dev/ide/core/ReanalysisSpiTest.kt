package dev.ide.core

import dev.ide.analysis.REANALYSIS
import dev.ide.analysis.Reanalysis
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `platform.reanalysis`: a plugin asks for the open files to be analyzed again. What it moves is the engine's
 * request counter, which the editor turns into a pass over its tabs; a request naming only closed files moves
 * nothing, since a closed file shows no diagnostics.
 */
class ReanalysisSpiTest {

    private val root = createTempDirectory("reanalysis-spi")
    private val services: IdeServices = IdeServices.bootstrapJavaDemo(root)

    @AfterTest
    fun tearDown() {
        services.close()
        root.toFile().deleteRecursively()
    }

    private val reanalysis: Reanalysis
        get() = services.store.workspace.service(REANALYSIS)

    private val javaFiles: List<Path> by lazy {
        Files.walk(services.workspaceRoot).use { s -> s.asSequence().filter { it.toString().endsWith(".java") }.sorted().toList() }
    }

    private fun open(file: Path) = services.updateDocument(file, file.readText())

    @Test
    fun `asking for every open file is one request`() {
        val before = services.reanalysisEpoch.value
        reanalysis.reanalyzeOpenFiles()
        assertEquals(before + 1, services.reanalysisEpoch.value)
    }

    @Test
    fun `asking for an open file is a request`() {
        val file = javaFiles.first()
        open(file)
        val before = services.reanalysisEpoch.value

        reanalysis.reanalyze(listOf(file.toString()))

        assertEquals(before + 1, services.reanalysisEpoch.value)
    }

    @Test
    fun `a path in another spelling still finds the open file`() {
        val file = javaFiles.first()
        open(file)
        val before = services.reanalysisEpoch.value

        reanalysis.reanalyze(listOf(file.parent.resolve("..").resolve(file.parent.fileName).resolve(file.fileName).toString()))

        assertEquals(before + 1, services.reanalysisEpoch.value)
    }

    @Test
    fun `asking only for closed files changes nothing`() {
        val (opened, closed) = javaFiles.take(2)
        open(opened)
        services.documentClosed(opened)
        val before = services.reanalysisEpoch.value

        reanalysis.reanalyze(listOf(opened.toString(), closed.toString(), "/no/such/File.java"))
        reanalysis.reanalyze(emptyList())

        assertEquals(before, services.reanalysisEpoch.value)
    }
}
