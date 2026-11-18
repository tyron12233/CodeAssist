package dev.codeassist.csharp.project.csproj

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CsprojGraphTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: Path get() = temp.root.toPath()

    /** Writes `<name>/<name>.csproj` referencing [references] (other project names), and returns the file. */
    private fun project(name: String, vararg references: String, slash: Char = '/'): Path {
        val dir = Files.createDirectories(root.resolve(name))
        val items = references.joinToString("") { "<ProjectReference Include=\"..$slash$it$slash$it.csproj\" />" }
        val file = dir.resolve("$name.csproj")
        Files.write(
            file,
            "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Library</OutputType></PropertyGroup><ItemGroup>$items</ItemGroup></Project>".toByteArray(),
        )
        return file
    }

    private fun source(path: String): Path {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, "class C {}".toByteArray())
        return file
    }

    private fun graph(file: Path) = CsprojGraph.referenced(file, Csproj.read(file))

    private fun names(result: CsprojGraph.Result) = result.nodes.map { it.assemblyName }

    @Test
    fun `a project with no references has none`() {
        val result = graph(project("App"))

        assertTrue(result.nodes.isEmpty())
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `references reach through other projects, dependencies first`() {
        project("Core")
        project("Data", "Core")
        val app = project("App", "Data")

        val result = graph(app)

        assertEquals(listOf("Core", "Data"), names(result))
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `a project reached twice is listed once`() {
        project("Core")
        project("Data", "Core")
        project("Web", "Core")
        val app = project("App", "Data", "Web")

        assertEquals(listOf("Core", "Data", "Web"), names(graph(app)))
    }

    @Test
    fun `a path written with backslashes is followed`() {
        project("Lib")
        val app = project("App", "Lib", slash = '\\')

        assertEquals(listOf("Lib"), names(graph(app)))
    }

    @Test
    fun `a missing project is a problem and the rest is kept`() {
        project("Lib")
        val app = project("App", "Gone", "Lib")

        val result = graph(app)

        assertEquals(listOf("Lib"), names(result))
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single(), "referenced project not found" in result.problems.single())
    }

    @Test
    fun `a cycle is a problem and does not loop`() {
        project("A", "B")
        project("B", "A")
        val app = project("App", "A")

        val result = graph(app)

        assertEquals(listOf("B", "A"), names(result))
        assertTrue(result.problems.single(), "cycle" in result.problems.single())
    }

    @Test
    fun `a referenced project builds into its own output folder`() {
        project("Lib")
        val app = project("App", "Lib")

        val node = graph(app).nodes.single()

        assertEquals(node.dir.resolve("bin/Debug/net10.0/Lib.dll"), node.output)
    }

    @Test
    fun `sources leave out a project nested in the folder`() {
        val app = project("App")
        val own = source("App/Program.cs")
        val deep = source("App/Models/Item.cs")
        Files.createDirectories(root.resolve("App/Inner"))
        Files.write(root.resolve("App/Inner/Inner.csproj"), "<Project />".toByteArray())
        source("App/Inner/Hidden.cs")
        source("App/bin/Debug/Generated.cs")

        val found = CsprojGraph.sources(app.parent, Csproj.read(app))

        assertEquals(listOf(deep, own).map { it.toString() }.sorted(), found.map { it.toString() }.sorted())
    }

    @Test
    fun `the owning folder is the nearest one with a project file`() {
        project("App")
        Files.createDirectories(root.resolve("App/Inner"))
        Files.write(root.resolve("App/Inner/Inner.csproj"), "<Project />".toByteArray())

        assertEquals(root.resolve("App").toAbsolutePath().normalize(), CsprojGraph.owningDir(source("App/Models/Item.cs")))
        assertEquals(root.resolve("App/Inner").toAbsolutePath().normalize(), CsprojGraph.owningDir(source("App/Inner/Hidden.cs")))
    }

    @Test
    fun `a file under no project has no owning folder`() {
        assertNull(CsprojGraph.owningDir(source("Loose/A.cs"), levels = 1))
    }
}
