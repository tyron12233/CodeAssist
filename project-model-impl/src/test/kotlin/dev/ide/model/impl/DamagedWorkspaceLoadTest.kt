package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.impl.PlatformCore
import dev.ide.testkit.withTempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Loading a workspace must survive damage to the files under it. This was the single largest error signal in
 * the field: a missing or malformed `module.toml` (most often a module directory deleted or moved outside the
 * IDE while `workspace.json` still listed it) threw out of [ModelPersistence.load], so the project could not
 * be opened at all and there was no way to repair it from inside the IDE.
 *
 * These pin the recovery policy: the workspace opens with whatever is readable, damaged pieces are skipped
 * rather than guessed at, a file that failed to parse is never rewritten, and the two hard failures that must
 * stay hard (no `workspace.json`, a newer schema) still throw.
 */
class DamagedWorkspaceLoadTest {

    /** A saved two-module workspace on disk, as the fixture every case then damages. */
    private fun twoModuleWorkspace(dir: Path) {
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
                    addSourceSet(
                        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE))),
                    )
                    addDependency(LibraryDependency(LibraryRef("g:a:1"), DependencyScope.IMPLEMENTATION))
                }
                addModule("shared", javaLib)
                commit()
            }
            store.save()
        } finally {
            platform.dispose()
        }
    }

    private fun loadModuleNames(dir: Path): List<String> =
        ModelPersistence.load(dir).projects.single().modules.map { it.name }.sorted()

    @Test
    fun aMissingModuleManifestLeavesTheRestOfTheWorkspaceOpenable() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        // The module directory was deleted outside the IDE; workspace.json still lists it.
        Files.delete(dir.resolve("shared/module.toml"))

        assertEquals(listOf("core"), loadModuleNames(dir), "the readable module must still load")
    }

    @Test
    fun anUnparsableModuleManifestIsSkippedAndLeftOnDisk() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        val broken = dir.resolve("shared/module.toml")
        val garbage = "[module\ntype = \"java-lib\""
        Files.writeString(broken, garbage)

        assertEquals(listOf("core"), loadModuleNames(dir))
        assertEquals(garbage, Files.readString(broken), "a manifest that failed to parse must not be rewritten")
    }

    @Test
    fun aModuleManifestMissingItsModuleTableIsSkippedRatherThanGuessed() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        // Parses as TOML, but has no [module] table, so there is no module type to honour.
        Files.writeString(dir.resolve("shared/module.toml"), "[sourceSets.main]\nscope = \"IMPLEMENTATION\"\n")

        assertEquals(listOf("core"), loadModuleNames(dir))
    }

    @Test
    fun aWorkspaceMissingItsSchemaVersionStillOpens() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        val ws = dir.resolve(".platform/workspace.json")
        Files.writeString(ws, Files.readString(ws).replace("\"version\": 1", "\"schema\": 1"))

        val data = ModelPersistence.load(dir)
        assertEquals(listOf("core", "shared"), data.projects.single().modules.map { it.name }.sorted())
        assertEquals(1, data.schemaVersion, "an absent version reads as the current schema")
    }

    @Test
    fun aSchemaVersionHeldAsAStringStillOpens() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        val ws = dir.resolve(".platform/workspace.json")
        Files.writeString(ws, Files.readString(ws).replace("\"version\": 1", "\"version\": \"1\""))

        assertEquals(listOf("core", "shared"), loadModuleNames(dir))
    }

    @Test
    fun unreadableSdksAndLibrariesCostAReResolveNotTheProject() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        Files.writeString(dir.resolve(".platform/sdks.json"), "{ \"sdks\": [ { \"name\": ")
        Files.writeString(dir.resolve(".platform/libraries.json"), "not json at all")

        val data = ModelPersistence.load(dir)
        assertEquals(listOf("core", "shared"), data.projects.single().modules.map { it.name }.sorted())
        assertTrue(data.sdks.isEmpty(), "derived platforms are dropped, to be re-detected")
        assertTrue(data.libraries.isEmpty(), "derived libraries are dropped, to be rebuilt")
    }

    @Test
    fun oneUnreadableProjectEntryDoesNotHideTheOthers() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        val ws = dir.resolve(".platform/workspace.json")
        // A second project entry with no `root`, as a partially written or hand-edited entry looks.
        val patched = Files.readString(ws).replace("\"projects\": [", "\"projects\": [ { \"id\": \"ghost\", \"name\": \"ghost\" },")
        Files.writeString(ws, patched)

        val data = ModelPersistence.load(dir)
        assertEquals(listOf("app"), data.projects.map { it.name }, "the readable project must still load")
    }

    @Test
    fun aMissingWorkspaceFileAndANewerSchemaStillFail() = withTempDir("codeassist-damaged") { dir ->
        twoModuleWorkspace(dir)
        val ws = dir.resolve(".platform/workspace.json")
        val original = Files.readString(ws)

        // A schema from a newer build must not be silently downgraded: opening it could destroy what it holds.
        Files.writeString(ws, original.replace("\"version\": 1", "\"version\": 99"))
        assertFailsWith<IllegalArgumentException> { ModelPersistence.load(dir) }

        // With no workspace.json there is nothing to recover, so this stays a hard failure.
        Files.delete(ws)
        assertFailsWith<java.nio.file.NoSuchFileException> { ModelPersistence.load(dir) }
    }
}
