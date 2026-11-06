package dev.ide.build.jvm.run

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.MissingResourceException
import org.jetbrains.compose.resources.readResourceBytes
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A run bridges `org/jetbrains/compose/resources`, so the IDE's own Compose resource reader serves the
 * program's `Res.*` lookups. Reported: a Compose Multiplatform desktop app run from the IDE opened on an
 * "Unknown error" dialog, because that reader only searched the IDE's class loader and threw
 * `MissingResourceException` for the project's `composeResources/…` file.
 */
@OptIn(InternalResourceApi::class)
class RunResourcesTest {

    private val path = "composeResources/kaitoani.sharedui.generated.resources/files/hello.txt"

    private fun read(p: String): ByteArray = runBlocking { readResourceBytes(p) }

    @Test
    fun theProgramsComposeResourcesResolveForTheRunAndOnlyForIt() {
        val dir = Files.createTempDirectory("run-res")
        dir.resolve(path).parent.createDirectories()
        dir.resolve(path).writeText("from the project")

        assertFailsWith<MissingResourceException>("the IDE's loader alone has never seen the project") { read(path) }

        val installed = assertNotNull(RunResources.install(javaClass.classLoader, listOf(dir)))
        installed.use {
            assertEquals("from the project", read(path).decodeToString())
            // The IDE's own resources still come from where they always did.
            assertTrue(read("kotlin/Unit.class").isNotEmpty(), "the original loader is still asked first")
        }

        assertFailsWith<MissingResourceException>("the reader is put back when the run ends") { read(path) }
    }

    @Test
    fun anEditedResourceIsReadAfreshByTheNextRun() {
        val dir = Files.createTempDirectory("run-res")
        val file = dir.resolve(path).also { it.parent.createDirectories() }
        file.writeText("first")
        RunResources.install(javaClass.classLoader, listOf(dir))!!.use { assertEquals("first", read(path).decodeToString()) }
        file.writeText("second")
        RunResources.install(javaClass.classLoader, listOf(dir))!!.use { assertEquals("second", read(path).decodeToString()) }
    }
}
