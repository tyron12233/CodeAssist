package dev.codeassist.csharp.project.csproj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path

class CsprojExtrasTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun read(body: String): CsprojInfo {
        val file = folder.root.toPath().resolve("App.csproj")
        file.toFile().writeText("<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>$body</PropertyGroup></Project>")
        return Csproj.read(file)
    }

    private fun args(
        info: CsprojInfo,
        forceLibrary: Boolean = false,
        resources: List<Pair<Path, String>> = emptyList(),
    ) =
        Csproj.arguments(
            info, emptyList(), emptyList(), java.nio.file.Paths.get("/o/App.dll"), null,
            forceLibrary = forceLibrary, resources = resources,
        )

    @Test
    fun `debug type and platform become flags in a build only`() {
        val info = read("<DebugType>Portable</DebugType><PlatformTarget>x64</PlatformTarget>")
        assertTrue("-debug:portable" in args(info))
        assertTrue("-platform:x64" in args(info))
        assertFalse("-debug:portable" in args(info, forceLibrary = true))
    }

    @Test
    fun `none and unknown words give no flag`() {
        assertNull(CsprojExtras.debugFlag(read("<DebugType>none</DebugType>")))
        assertNull(CsprojExtras.platformFlag(read("<PlatformTarget>mips</PlatformTarget>")))
    }

    @Test
    fun `documentation file is written beside the dll`() {
        assertTrue("-doc:/o/App.xml" in args(read("<GenerateDocumentationFile>true</GenerateDocumentationFile>")))
        assertFalse(args(read("")).any { it.startsWith("-doc:") })
    }

    @Test
    fun `resources become resource flags`() {
        val info = read("")
        val flags = args(info, resources = listOf(java.nio.file.Paths.get("/p/a.txt") to "App.a.txt"))
        assertTrue("-resource:/p/a.txt,App.a.txt" in flags)
    }

    @Test
    fun `resource names use the namespace and dots`() {
        assertEquals("My.Ns.data.my_file.json", CsprojExtras.resourceName("My.Ns", "data/my file.json"))
        assertEquals("a.txt", CsprojExtras.resourceName("", "a.txt"))
    }

    @Test
    fun `embedded resources are found under the project outside bin and obj`() {
        val dir = folder.root.toPath()
        val info = read("<RootNamespace>Demo</RootNamespace>").copy(embeddedResources = listOf("Assets/**"))
        listOf("Assets/a.txt", "Assets/sub/b.txt", "obj/Assets/c.txt", "Other/d.txt").forEach {
            val f = dir.resolve(it).toFile()
            f.parentFile.mkdirs()
            f.writeText("x")
        }
        val found = CsprojExtras.resources(info, dir).map { it.second }
        assertEquals(listOf("Demo.Assets.a.txt", "Demo.Assets.sub.b.txt"), found)
    }

    @Test
    fun `assembly info is empty when nothing is set or generation is off`() {
        assertEquals("", CsprojExtras.assemblyInfoText(read("")))
        val off = read("<Version>1.0</Version><GenerateAssemblyInfo>false</GenerateAssemblyInfo>")
        assertEquals("", CsprojExtras.assemblyInfoText(off))
    }

    @Test
    fun `version gives numeric assembly and file versions and the informational as written`() {
        val text = CsprojExtras.assemblyInfoText(read("<Version>1.2.3-beta</Version><Company>Me \"Co\"</Company>"))
        assertTrue(text.contains("AssemblyVersionAttribute(\"1.2.3.0\")"))
        assertTrue(text.contains("AssemblyFileVersionAttribute(\"1.2.3.0\")"))
        assertTrue(text.contains("AssemblyInformationalVersionAttribute(\"1.2.3-beta\")"))
        assertTrue(text.contains("AssemblyCompanyAttribute(\"Me \\\"Co\\\"\")"))
    }

    @Test
    fun `explicit versions win and authors stand in for the company`() {
        val text = CsprojExtras.assemblyInfoText(
            read("<Version>1.0.0</Version><AssemblyVersion>2.0.0.0</AssemblyVersion><Authors>Aio</Authors>"),
        )
        assertTrue(text.contains("AssemblyVersionAttribute(\"2.0.0.0\")"))
        assertTrue(text.contains("AssemblyCompanyAttribute(\"Aio\")"))
    }

    @Test
    fun `numeric version`() {
        assertEquals("1.2.0.0", CsprojExtras.numericVersion("1.2"))
        assertEquals("1.2.3.4", CsprojExtras.numericVersion("1.2.3.4+build"))
        assertNull(CsprojExtras.numericVersion("one.two"))
        assertNull(CsprojExtras.numericVersion("1.2.3.4.5"))
    }
}
