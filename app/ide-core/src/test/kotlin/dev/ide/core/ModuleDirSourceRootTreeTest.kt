package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeExtensionPoint
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import dev.ide.platform.PluginId
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiSearchOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A module whose source root is its own directory (a language with no source folder: `dotnet new console`
 * puts the project file and `Program.cs` side by side), with `bin/` and `obj/` declared as derived output.
 * The tree lists the directory once, draws the output folders as output rather than as packages, and a
 * project sweep stays out of them.
 */
class ModuleDirSourceRootTreeTest {

    private fun flatten(n: TreeNode): List<TreeNode> = listOf(n) + n.children.flatMap(::flatten)

    /** A module type on a platform the IDE does not own, the way a language plugin declares one. */
    private object CSharpAppType : ModuleType {
        override val id: String = "csharp-app"
        override val displayName: String = "C# Application"
        override val platform: PlatformKind get() = PlatformKind("DOTNET")
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    /**
     * The Java demo with its `core` module rewritten on disk: [type], the module directory as the one source
     * root, `bin/` and `obj/` as excluded roots, and files in all three. Returns the module directory.
     */
    private fun layOut(dir: Path, type: String): Path {
        IdeServices.bootstrapJavaDemo(dir).use { }
        val moduleDir = dir.resolve("core")
        val toml = moduleDir.resolve("module.toml")
        toml.toFile().writeText(
            toml.toFile().readText()
                .replace("type = \"java-lib\"", "type = \"$type\"")
                .replace("java = [\"src/main/java\"]", "java = [\"\"]\nexcluded = [\"bin\", \"obj\"]"),
        )
        moduleDir.resolve("src").toFile().deleteRecursively()
        Files.createDirectories(moduleDir.resolve("bin/Debug/net10.0"))
        Files.createDirectories(moduleDir.resolve("obj/codeassist"))
        Files.createDirectories(moduleDir.resolve("Models/Dto"))
        moduleDir.resolve("Program.cs").toFile().writeText("// needle\n")
        moduleDir.resolve("Models/Dto/User.cs").toFile().writeText("// needle\n")
        moduleDir.resolve("bin/Debug/net10.0/App.deps.json").toFile().writeText("{ \"needle\": 1 }\n")
        moduleDir.resolve("obj/codeassist/Generated.cs").toFile().writeText("// needle\n")
        return moduleDir
    }

    @Test
    fun moduleDirectoryAsSourceRootIsListedOnceWithOutputFoldersDimmed() {
        withTempDir("ide-tree-moduledir") { dir ->
            val moduleDir = layOut(dir, CSharpAppType.id)
            IdeServices.open(dir).use { ide ->
                ide.platform.extensions.register(ModuleTypeExtensionPoint, CSharpAppType, PluginId("test-plugin"))
                val backend = IdeServicesBackend(ide)
                val module = backend.files.fileTree().children.first { it.kind == NodeKind.Module && it.name == "core" }
                val nodes = flatten(module)

                // The directory is drawn once: no node for the root, and each file a single time.
                assertTrue(module.children.none { it.kind == NodeKind.SourceRoot }, "no separate root node: ${module.children.map { it.name }}")
                assertEquals(1, nodes.count { it.name == "Program.cs" })
                assertEquals(1, nodes.count { it.name == "module.toml" })
                assertNotNull(module.children.firstOrNull { it.name == "Program.cs" }, "the root's files sit under the module")
                assertNotNull(nodes.first { it.name == "module.toml" }.moduleConfigName, "module.toml still opens module settings")

                // A source folder is a plain folder, not a dotted package: this is not a JVM module.
                val models = module.children.first { it.name == "Models" }
                assertEquals(NodeKind.Folder, models.kind)
                assertEquals(listOf("Dto"), models.children.map { it.name })
                assertTrue(nodes.none { it.kind == NodeKind.Package }, "no package nodes: ${nodes.filter { it.kind == NodeKind.Package }.map { it.name }}")

                // The declared output folders are real folders drawn as output, all the way down.
                for (name in listOf("bin", "obj")) {
                    val out = module.children.first { it.name == name }
                    assertEquals(NodeKind.Folder, out.kind)
                    assertTrue(flatten(out).all { it.styleHint == "excluded" }, "$name is dimmed throughout")
                }
                assertEquals(listOf("Debug"), module.children.first { it.name == "bin" }.children.map { it.name })
                assertNull(nodes.first { it.name == "Program.cs" }.styleHint)

                // Find in Files reads the sources and stays out of the output.
                val hits = ide.search.findInFiles("needle", UiSearchOptions(), 50).map { it.fileName }
                assertEquals(setOf("Program.cs", "User.cs"), hits.toSet())

                // The All Files view draws the same folders as output too.
                val all = flatten(backend.files.fileTree(TreeViewMode.AllFiles))
                assertEquals("excluded", all.first { it.dirPath?.startsWith(moduleDir.resolve("obj").toString()) == true }.styleHint)
            }
            dir.toFile().deleteRecursively()
        }
    }

    /** The same layout on a JVM module keeps its packages; only the output folders change. */
    @Test
    fun jvmModuleRootedAtItsDirectoryKeepsPackagesButNotForOutput() {
        withTempDir("ide-tree-moduledir-jvm") { dir ->
            layOut(dir, "java-lib")
            IdeServices.open(dir).use { ide ->
                val module = IdeServicesBackend(ide).files.fileTree().children.first { it.kind == NodeKind.Module && it.name == "core" }
                assertEquals(NodeKind.Package, module.children.first { it.name == "Models.Dto" }.kind)
                val bin = module.children.first { it.name == "bin" }
                assertEquals(NodeKind.Folder, bin.kind, "an output folder is never a package")
                assertEquals("excluded", bin.styleHint)
                assertEquals(1, flatten(module).count { it.name == "module.toml" })
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The build and the index of a JVM module rooted at its own directory: what sits in `bin/` and in `build/`
     * is not compiled or indexed, while the generated-sources root under `build/` still is.
     */
    @Test
    fun buildAndIndexStayOutOfOutputFoldersButKeepTheGeneratedRoot() {
        withTempDir("ide-tree-moduledir-build") { dir ->
            val moduleDir = layOut(dir, "java-lib")
            moduleDir.resolve("App.java").toFile().writeText("public class App {}\n")
            moduleDir.resolve("bin/Dup.java").toFile().writeText("public class StaleDup {}\n")
            Files.createDirectories(moduleDir.resolve("build/stale"))
            moduleDir.resolve("build/stale/Old.java").toFile().writeText("public class StaleOld {}\n")
            Files.createDirectories(moduleDir.resolve("build/generated"))
            moduleDir.resolve("build/generated/Gen.java").toFile().writeText("public class GenThing {}\n")
            IdeServices.open(dir).use { ide ->
                val module = ide.modules().first { it.name == "core" }
                assertEquals(
                    setOf("App.java", "Gen.java"),
                    dev.ide.build.engine.sourceFiles(module).map { it.fileName.toString() }.toSet(),
                    "the compile takes the sources and the generated root, not stale output",
                )

                val deadline = System.currentTimeMillis() + 180_000
                while (ide.indexStatus.value.message != "Indexed" && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertEquals("Indexed", ide.indexStatus.value.message)
                assertTrue(ide.search.searchSymbols("App", 50).any { it.name == "App" }, "the source is indexed")
                assertTrue(ide.search.searchSymbols("Stale", 50).isEmpty(), "output folders are not indexed")
            }
            dir.toFile().deleteRecursively()
        }
    }
}
