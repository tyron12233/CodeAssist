package dev.codeassist.dart

import dev.ide.model.DependencyScope
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.SyncReason
import dev.ide.model.sync.SyncRequest
import dev.ide.platform.ProgressReporter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PubImporterTest {

    @TempDir
    lateinit var dir: Path

    private fun write(rel: String, text: String = "") {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
    }

    private val noProgress = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled = false
    }

    @Test
    fun aFolderWithAPubspecIsAdoptedAsOneDartPackageModule() {
        assertNull(PubImporter.detect(dir), "no pubspec, not a Dart project")
        write("pubspec.yaml", "name: weather_cli\nenvironment:\n  sdk: ^3.0.0\n")
        write("bin/weather.dart", "void main() {}\n")
        write("test/weather_test.dart")

        val detection = PubImporter.detect(dir)!!
        assertEquals("weather_cli", detection.name)

        val outcome = runBlocking { PubImporter.resolve(SyncRequest(dir, noProgress, SyncReason.IMPORT)) }
        val model = outcome.model!!
        val module = model.modules.single()
        assertEquals("weather_cli", module.name)
        assertEquals(".", module.dirRelPath, "the package is the project root (a blank path would mean a directory named after the module)")
        assertEquals(DartModuleType.ID, module.typeId)
        assertEquals(
            mapOf("main" to setOf("lib", "bin"), "test" to setOf("test")),
            module.sourceSets.associate { it.name to it.roots.keys },
        )
        assertEquals(DependencyScope.TEST_IMPLEMENTATION, module.sourceSets.first { it.name == "test" }.scope)
        // Adopted into a model the IDE owns, synced before a build when pub get changed the package config
        // (which decides a Flutter app's plugin modules) or an Android app's build file changed.
        assertEquals(ModelOwnership.IDE, PubImporter.ownership)
        assertTrue(PubImporter.syncBeforeBuild)
        assertTrue(DartPackages.PACKAGE_CONFIG in PubImporter.syncFiles())
        // A plain Dart package has no Android side: no app module, no Flutter repository.
        assertEquals(1, outcome.model!!.modules.size)
        assertTrue(outcome.model!!.repositories.isEmpty())
    }

    @Test
    fun aPubspecWithoutANameFallsBackToTheFolder() {
        write("pubspec.yaml", "environment:\n  sdk: ^3.0.0\n")
        val name = PubImporter.detect(dir)!!.name
        assertEquals(DartPackages.packageName(dir.fileName.toString()), name)
    }
}
