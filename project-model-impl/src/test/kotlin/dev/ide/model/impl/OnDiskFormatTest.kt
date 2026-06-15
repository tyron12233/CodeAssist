package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.SdkDependency
import dev.ide.model.SdkRef
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.impl.PlatformCore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Pins the human-readable on-disk shape: a declarative `module.toml` manifest and a `workspace.json`.
 * Acts as a regression guard on the serialized format, separate from the structural round-trip in
 * [PersistenceRoundTripTest].
 */
class OnDiskFormatTest {

    @Test
    fun writesReadableWorkspaceJsonAndModuleToml() {
        val dir = Files.createTempDirectory("codeassist-format")
        val platform = PlatformCore()
        platform.registerTestTypes()
        try {
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry().register(JavaFacetCodec))
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")

            store.workspace.beginModification().apply {
                addProject("app", BuildSystemId.NATIVE, store.vfs.root())
                commit()
            }
            store.workspace.projects.single().beginModification().apply {
                addModule("core", javaLib).apply {
                    addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))))
                    addDependency(ModuleDependency(ModuleId("shared"), DependencyScope.API, exported = true))
                    addDependency(LibraryDependency(LibraryRef("g:a:1"), DependencyScope.IMPLEMENTATION))
                    addDependency(SdkDependency(SdkRef("jdk-17"), DependencyScope.COMPILE_ONLY))
                    putFacet(JavaFacet(listOf("dagger.X"), preview = false))
                }
                commit()
            }
            store.save()

            val workspaceJson = Files.readString(dir.resolve(".platform/workspace.json"))
            assertContains(workspaceJson, "\"version\": 1")
            assertContains(workspaceJson, "\"buildSystem\": \"native\"")
            assertContains(workspaceJson, "\"id\": \"core\"")

            val moduleToml = Files.readString(dir.resolve("core/module.toml"))
            assertContains(moduleToml, "version = 1")
            assertContains(moduleToml, "[module]")
            assertContains(moduleToml, "type = \"java-lib\"")
            assertContains(moduleToml, "[sourceSets.main]")
            assertContains(moduleToml, "scope = \"IMPLEMENTATION\"")
            assertContains(moduleToml, "java = [\"src/main/java\"]")
            assertContains(moduleToml, "[dependencies]")
            assertContains(moduleToml, "api = [{ module = \"shared\" }]")
            assertContains(moduleToml, "implementation = [\"g:a:1\"]")
            assertContains(moduleToml, "compileOnly = [{ sdk = \"jdk-17\" }]")
            assertContains(moduleToml, "[java]")
            assertContains(moduleToml, "preview = false")
        } finally {
            platform.dispose()
            dir.toFile().deleteRecursively()
        }
    }
}
