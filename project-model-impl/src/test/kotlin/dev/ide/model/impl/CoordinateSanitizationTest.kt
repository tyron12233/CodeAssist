package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.SourceSetTemplate
import dev.ide.model.sanitizeCoordinate
import dev.ide.model.sanitizeLibraryName
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A coordinate pasted from a documentation code block can carry a zero-width space, which survives
 * `String.trim()` and turns the artifact URL into a 404 against a coordinate that looks correct on
 * screen. Declarations are normalized as they are read back out of `module.toml`, so a project that
 * already persisted one heals on load.
 */
class CoordinateSanitizationTest {

    @Test
    fun stripsEveryInvisibleCharacterAndLeavesTheRestAlone() {
        // a zero-width space in the group, which is how AndroidX documentation copies out
        assertEquals(
            "androidx.lifecycle:lifecycle-process:2.11.0",
            sanitizeCoordinate("​androidx.lifecycle:lifecycle-process:2.11.0"),
        )
        // and in the version
        assertEquals(
            "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0",
            sanitizeCoordinate("org.jetbrains.kotlinx:kotlinx-serialization-json:​1.11.0"),
        )
        // zero-width joiner, soft hyphen, byte-order mark, non-breaking space, ordinary whitespace
        assertEquals("g:a:1.0", sanitizeCoordinate("g:a‍:1­.0"))
        assertEquals("g:a:1.0", sanitizeCoordinate("﻿g:a : 1.0\n"))
        // a clean coordinate is returned unchanged
        assertEquals("androidx.compose:compose-bom-alpha:2026.07.01", sanitizeCoordinate("androidx.compose:compose-bom-alpha:2026.07.01"))
    }

    /** A library name is a coordinate only when it carries a `:`; the other forms must survive untouched,
     *  including a local jar whose file name contains spaces. */
    @Test
    fun leavesANonCoordinateLibraryNameAlone() {
        assertEquals("kotlin-stdlib", sanitizeLibraryName("kotlin-stdlib"))
        assertEquals("my vendor lib.jar", sanitizeLibraryName("my vendor lib.jar"))
        assertEquals("androidx.compose.ui:ui:1.12.0", sanitizeLibraryName("androidx.compose.ui:ui:​1.12.0"))
    }

    @Test
    fun aPersistedCoordinateWithAZeroWidthSpaceHealsOnLoad() {
        withTempDir("codeassist-zwsp-heal") { dir ->
            val platform = PlatformCore()
            platform.registerTestTypes()
            try {
                val codecs = FacetCodecRegistry().register(JavaFacetCodec)
                ProjectModel.open(dir, platform, codecs).let { store ->
                    val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
                    store.workspace.beginModification().apply {
                        addProject("app", BuildSystemId.NATIVE, store.vfs.root()); commit()
                    }
                    store.workspace.projects.single().beginModification().apply {
                        addModule("core", javaLib).addSourceSet(
                            SourceSetTemplate(
                                "main",
                                DependencyScope.IMPLEMENTATION,
                                mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
                            )
                        )
                        commit()
                    }
                    store.save()
                }

                // Declare the coordinate the way a paste into the Add field used to persist it: verbatim.
                val toml = dir.resolve("core/module.toml")
                Files.writeString(
                    toml,
                    Files.readString(toml) + "\n[dependencies]\n" +
                        "implementation = [\"​androidx.lifecycle:lifecycle-process:2.11.0\", " +
                        "{ platform = \"androidx.compose:compose-bom-alpha:​2026.07.01\" }]\n",
                )

                val reloaded = ProjectModel.open(dir, platform, codecs)
                val module = reloaded.workspace.projects.single().modules.single { it.name == "core" }
                assertEquals(
                    "androidx.lifecycle:lifecycle-process:2.11.0",
                    module.dependencies.filterIsInstance<LibraryDependency>().single().library.name,
                )
                assertEquals(
                    "2026.07.01",
                    module.dependencies.filterIsInstance<PlatformDependency>().single().bom.version,
                )

                // and the healed name is what the next save puts back on disk
                reloaded.workspace.projects.single().beginModification().apply {
                    module(ModuleId("core")).addDependency(
                        LibraryDependency(LibraryRef("g:a:1"), DependencyScope.IMPLEMENTATION)
                    )
                    commit()
                }
                reloaded.save()
                assertFalse(
                    Files.readString(toml).contains('​'),
                    "module.toml still holds a zero-width space",
                )
            } finally {
                platform.dispose()
            }
        }
    }
}
