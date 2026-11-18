package dev.codeassist.csharp.project.csproj

import dev.codeassist.csharp.newTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CsprojTest {

    private val root: Path = newTempDir()

    private fun write(relative: String, text: String = ""): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, text.toByteArray())
        return path
    }

    private fun project(body: String, name: String = "App.csproj"): CsprojInfo =
        Csproj.read(write(name, "<Project Sdk=\"Microsoft.NET.Sdk\">$body</Project>"))

    private fun info(
        assemblyName: String? = null,
        outputType: String = "Exe",
        outDir: String? = null,
        appendTargetFramework: Boolean = true,
        targetFramework: String? = null,
        projectName: String? = null,
        includes: List<String> = emptyList(),
        removes: List<String> = emptyList(),
        references: List<CsprojReference> = emptyList(),
    ) = CsprojInfo(
        assemblyName, outputType, null, false, false, emptyList(), includes, removes, emptyList(), emptyList(),
        targetFramework, references, outDir, appendTargetFramework, projectName,
    )

    // ---- read ----

    @Test
    fun `properties are read`() {
        val read = project(
            """
            <PropertyGroup>
              <OutputType>Exe</OutputType>
              <TargetFramework>net10.0</TargetFramework>
              <AssemblyName>MyApp</AssemblyName>
              <LangVersion>12</LangVersion>
              <Nullable>enable</Nullable>
              <ImplicitUsings>enable</ImplicitUsings>
              <DefineConstants>DEBUG;TRACE</DefineConstants>
              <StartupObject>N.Cli</StartupObject>
            </PropertyGroup>
            """,
        )
        assertEquals("N.Cli", read.startupObject)
        assertEquals("Exe", read.outputType)
        assertEquals("net10.0", read.targetFramework)
        assertEquals("MyApp", read.assemblyName)
        assertEquals("12", read.langVersion)
        assertTrue(read.nullable)
        assertTrue(read.implicitUsings)
        assertEquals(listOf("DEBUG", "TRACE"), read.defines)
        assertEquals("App", read.projectName)
    }

    @Test
    fun `a project that says nothing is a library with nothing enabled`() {
        val read = project("")
        assertEquals("Library", read.outputType)
        assertTrue(read.isLibrary)
        assertFalse(read.nullable)
        assertFalse(read.implicitUsings)
        assertNull(read.assemblyName)
        assertNull(read.outDir)
        assertTrue(read.appendTargetFramework)
        assertEquals(emptyList<String>(), read.defines)
    }

    @Test
    fun `the last value of a property wins`() {
        val read = project("<PropertyGroup><OutputType>Library</OutputType></PropertyGroup>" +
            "<PropertyGroup><OutputType>Exe</OutputType></PropertyGroup>")
        assertEquals("Exe", read.outputType)
        assertFalse(read.isLibrary)
    }

    @Test
    fun `an empty element does not replace an earlier value`() {
        val read = project("<PropertyGroup><AssemblyName>First</AssemblyName></PropertyGroup>" +
            "<PropertyGroup><AssemblyName></AssemblyName></PropertyGroup>")
        assertEquals("First", read.assemblyName)
    }

    @Test
    fun `output type is compared without regard to case`() {
        assertTrue(project("<PropertyGroup><OutputType>library</OutputType></PropertyGroup>").isLibrary)
    }

    @Test
    fun `the project name is the file name without its extension`() {
        assertEquals("MinisharkTurbo", project("", "MinisharkTurbo.csproj").projectName)
    }

    @Test
    fun `OutDir and the target framework switch are read`() {
        val read = project(
            "<PropertyGroup><OutDir>out\\mods</OutDir>" +
                "<AppendTargetFrameworkToOutputPath>false</AppendTargetFrameworkToOutputPath></PropertyGroup>",
        )
        assertEquals("out\\mods", read.outDir)
        assertFalse(read.appendTargetFramework)
    }

    @Test
    fun `compile items are read with their separators normalised`() {
        val read = project(
            """
            <ItemGroup>
              <Compile Include="src\**\*.cs;extra/Util.cs" />
              <Compile Remove="src\Old\**" />
            </ItemGroup>
            """,
        )
        assertEquals(listOf("src/**/*.cs", "extra/Util.cs"), read.includes)
        assertEquals(listOf("src/Old/**"), read.removes)
    }

    @Test
    fun `package and project references are read`() {
        val read = project(
            """
            <ItemGroup>
              <PackageReference Include="Newtonsoft.Json" Version="13.0.1" />
              <ProjectReference Include="..\Lib\Lib.csproj" />
            </ItemGroup>
            """,
        )
        assertEquals(listOf(CsprojPackage("Newtonsoft.Json", "13.0.1")), read.packageReferences)
        assertEquals(listOf("../Lib/Lib.csproj"), read.projectReferences)
    }

    @Test
    fun `a reference with a hint path as attribute`() {
        val read = project(
            """<ItemGroup><Reference Include="BunnyLoader" HintPath="libs\BunnyLoader.dll" /></ItemGroup>""",
        )
        assertEquals(listOf(CsprojReference("BunnyLoader", "libs/BunnyLoader.dll", true)), read.references)
    }

    @Test
    fun `a reference as Visual Studio writes it, with children`() {
        val read = project(
            """
            <ItemGroup>
              <Reference Include="Terraria, Version=1.4.4.9, Culture=neutral">
                <HintPath>..\libs\Terraria.dll</HintPath>
                <Private>false</Private>
              </Reference>
            </ItemGroup>
            """,
        )
        assertEquals(listOf(CsprojReference("Terraria", "../libs/Terraria.dll", false)), read.references)
    }

    @Test
    fun `a reference name loses its dll suffix`() {
        val read = project("""<ItemGroup><Reference Include="Lib.dll" /></ItemGroup>""")
        assertEquals("Lib", read.references.single().name)
        assertNull(read.references.single().hintPath)
    }

    @Test
    fun `a reference with no name is dropped`() {
        assertTrue(project("""<ItemGroup><Reference Include="" /></ItemGroup>""").references.isEmpty())
    }

    @Test(expected = Exception::class)
    fun `a file that is not XML throws`() {
        Csproj.read(write("Bad.csproj", "this is not xml"))
    }

    // ---- find ----

    @Test
    fun `find takes the project directly in the folder`() {
        val file = write("App.csproj", "<Project/>")
        assertEquals(file, Csproj.find(root))
    }

    @Test
    fun `find looks in subfolders when there is none directly`() {
        val file = write("src/App/App.csproj", "<Project/>")
        assertEquals(file, Csproj.find(root))
    }

    @Test
    fun `find does not go deeper than three folders`() {
        write("a/b/c/d/Deep.csproj", "<Project/>")
        assertNull(Csproj.find(root))
    }

    @Test
    fun `find ignores bin and obj`() {
        write("bin/Old.csproj", "<Project/>")
        write("obj/Other.csproj", "<Project/>")
        assertNull(Csproj.find(root))
    }

    @Test
    fun `find prefers the project in the folder itself`() {
        val direct = write("Top.csproj", "<Project/>")
        write("sub/Sub.csproj", "<Project/>")
        assertEquals(direct, Csproj.find(root))
    }

    @Test
    fun `find with no project or no folder is null`() {
        write("src/Program.cs", "class P {}")
        assertNull(Csproj.find(root))
        assertNull(Csproj.find(root.resolve("missing")))
    }

    // ---- outputDir ----

    @Test
    fun `output goes to bin Debug with the framework by default`() {
        assertEquals(root.resolve("bin/Debug/net10.0"), Csproj.outputDir(root, null))
        assertEquals(root.resolve("bin/Debug/net9.0"), Csproj.outputDir(root, info(targetFramework = "net9.0")))
    }

    @Test
    fun `output without the framework folder when appending is off`() {
        assertEquals(root.resolve("bin/Debug"), Csproj.outputDir(root, info(appendTargetFramework = false)))
    }

    @Test
    fun `OutDir wins over everything`() {
        val withOutDir = info(outDir = "mods\\out", appendTargetFramework = false, targetFramework = "net9.0")
        assertEquals(root.resolve("mods/out"), Csproj.outputDir(root, withOutDir))
    }

    @Test
    fun `OutDir may climb out of the project folder`() {
        assertEquals(root.resolve("shared"), Csproj.outputDir(root, info(outDir = "sub/../shared")))
    }

    @Test
    fun `output is relative to the folder of the project file`() {
        write("src/App/App.csproj", "<Project/>")
        assertEquals(root.resolve("src/App/bin/Debug/net10.0"), Csproj.outputDir(root, info()))
    }

    // ---- assemblyName ----

    @Test
    fun `AssemblyName wins`() {
        assertEquals("Mod", Csproj.assemblyName(info(assemblyName = "Mod", projectName = "Other"), "Module"))
    }

    @Test
    fun `the project file name comes next`() {
        assertEquals("MinisharkTurbo", Csproj.assemblyName(info(projectName = "MinisharkTurbo"), "Module"))
    }

    @Test
    fun `the module name is the last resort`() {
        assertEquals("test2", Csproj.assemblyName(null, "test2"))
        assertEquals("test2", Csproj.assemblyName(info(), "test2"))
    }

    @Test
    fun `the name is made valid`() {
        assertEquals("MyApp", Csproj.assemblyName(info(assemblyName = "My App!"), "m"))
        assertEquals("App1x", Csproj.assemblyName(info(assemblyName = "1x"), "m"))
        assertEquals("App", Csproj.assemblyName(null, "!!!"))
    }

    // ---- sources ----

    @Test
    fun `with no includes every candidate is compiled`() {
        val candidates = listOf(root.resolve("a.cs"), root.resolve("src/b.cs"))
        assertEquals(candidates, Csproj.sources(info(), root, candidates))
    }

    @Test
    fun `includes and removes filter the candidates`() {
        val a = root.resolve("src/a.cs")
        val b = root.resolve("src/Old/b.cs")
        val c = root.resolve("tools/c.cs")
        val filtered = Csproj.sources(
            info(includes = listOf("src/**/*.cs"), removes = listOf("src/Old/**")),
            root,
            listOf(a, b, c),
        )
        assertEquals(listOf(a), filtered)
    }

    // ---- references ----

    @Test
    fun `references are found by hint path, then in the folder, then in lib`() {
        val hinted = write("libs/Hinted.dll")
        val beside = write("Beside.dll")
        val inLib = write("lib/InLib.dll")
        val read = info(
            references = listOf(
                CsprojReference("Hinted", "libs/Hinted.dll"),
                CsprojReference("Beside", null),
                CsprojReference("InLib", null),
                CsprojReference("Missing", null),
            ),
        )
        val (found, missing) = Csproj.resolveReferences(read, root)
        assertEquals(listOf(hinted, beside, inLib).map { it.normalize() }, found)
        assertEquals(listOf("Missing"), missing)
    }

    @Test
    fun `a hint path is relative to the project file, not the module folder`() {
        write("src/App/App.csproj", "<Project/>")
        val dll = write("src/App/libs/Lib.dll")
        val read = info(references = listOf(CsprojReference("Lib", "libs/Lib.dll")))
        val (found, _) = Csproj.resolveReferences(read, root)
        assertEquals(listOf(dll.normalize()), found)
    }

    @Test
    fun `copy local leaves out Private false references`() {
        val copied = write("Copied.dll")
        write("Kept.dll")
        val read = info(
            references = listOf(
                CsprojReference("Copied", null, copyLocal = true),
                CsprojReference("Kept", null, copyLocal = false),
            ),
        )
        assertEquals(listOf(copied.normalize()), Csproj.copyLocal(read, root))
    }

    // ---- unsupported ----

    @Test
    fun `package references are supported`() {
        val packages = listOf(CsprojPackage("A", "1.0"), CsprojPackage("B", null))
        assertNull(Csproj.unsupported(info().copy(packageReferences = packages)))
    }

    @Test
    fun `a package version may be an element or an override`() {
        val read = project(
            """
            <ItemGroup>
              <PackageReference Include="A"><Version>2.1.0</Version></PackageReference>
              <PackageReference Include="B" VersionOverride="3.0.0" />
              <PackageReference Include="C" />
            </ItemGroup>
            """,
        )
        assertEquals(
            listOf(CsprojPackage("A", "2.1.0"), CsprojPackage("B", "3.0.0"), CsprojPackage("C", null)),
            read.packageReferences,
        )
    }

    @Test
    fun `project references are not supported`() {
        assertNotNull(Csproj.unsupported(info().copy(projectReferences = listOf("../Lib.csproj"))))
    }

    @Test
    fun `a plain project is supported`() {
        assertNull(Csproj.unsupported(info()))
    }

    // ---- arguments ----

    @Test
    fun `arguments follow the project`() {
        val out = root.resolve("out/App.dll")
        val usings = root.resolve("GlobalUsings.g.cs")
        val read = info(outputType = "Exe").copy(langVersion = "12", nullable = true, defines = listOf("A", "B"))
        val arguments = Csproj.arguments(read, listOf(root.resolve("a.cs")), listOf(root.resolve("r.dll")), out, usings)
        assertEquals(
            listOf(
                "-nologo", "-noconfig", "-target:exe", "-langversion:12", "-nullable:enable",
                "-define:DEBUG;TRACE;NET;NETCOREAPP;NET10_0;NET10_0_OR_GREATER;A;B",
                "-out:$out", "-r:${root.resolve("r.dll")}", root.resolve("a.cs").toString(), usings.toString(),
            ),
            arguments,
        )
    }

    @Test
    fun `a library is built as a library`() {
        val library = info(outputType = "Library")
        val arguments = Csproj.arguments(library, emptyList(), emptyList(), root.resolve("o.dll"), null)
        assertTrue("-target:library" in arguments)
    }

    @Test
    fun `forceLibrary overrides an exe project`() {
        val arguments = Csproj.arguments(
            info(outputType = "Exe"), emptyList(), emptyList(), root.resolve("o.dll"), null, forceLibrary = true,
        )
        assertTrue("-target:library" in arguments)
        assertFalse("-target:exe" in arguments)
    }

    @Test
    fun `optional switches are left out when the project does not set them`() {
        val arguments = Csproj.arguments(info(), emptyList(), emptyList(), root.resolve("o.dll"), null)
        assertEquals(
            listOf(
                "-nologo", "-noconfig", "-target:exe", "-define:DEBUG;TRACE;NET;NETCOREAPP;NET10_0;NET10_0_OR_GREATER",
                "-out:${root.resolve("o.dll")}",
            ),
            arguments,
        )
    }

    @Test
    fun `global usings list the implicit namespaces`() {
        val text = Csproj.globalUsingsText(info().copy(implicitUsings = true))
        assertTrue(text.contains("global using global::System;\n"))
        assertTrue(text.contains("global using global::System.Threading.Tasks;\n"))
        assertEquals(7, text.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `a main class becomes the compilers -main`() {
        val info = info()
        val out = Paths.get("/o/a.dll")
        assertTrue("-main:N.Cli" in Csproj.arguments(info, emptyList(), emptyList(), out, null, mainClass = "N.Cli"))
        assertTrue(Csproj.arguments(info, emptyList(), emptyList(), out, null).none { it.startsWith("-main:") })
        // The editor's check compiles as a library, which has no entry point to choose.
        val check = Csproj.arguments(info, emptyList(), emptyList(), out, null, forceLibrary = true, mainClass = "N.Cli")
        assertTrue(check.none { it.startsWith("-main:") })
    }

    @Test
    fun `only a csproj path names a project folder`() {
        assertEquals(java.nio.file.Paths.get("/p/src"), Csproj.dirOfProjectFile("/p/src/../src/App.csproj"))
        assertEquals(java.nio.file.Paths.get("/p/src"), Csproj.dirOfProjectFile("/p/src/App.CSPROJ"))
        assertNull(Csproj.dirOfProjectFile("/p/src/Program.cs"))
        assertNull(Csproj.dirOfProjectFile("/p/src/App.csproj.bak"))
    }
}

class GlobToRegexTest {

    private fun matches(glob: String, path: String) = globToRegex(glob).matches(path)

    @Test
    fun `a star stays inside a folder`() {
        assertTrue(matches("src/*.cs", "src/a.cs"))
        assertFalse(matches("src/*.cs", "src/sub/a.cs"))
    }

    @Test
    fun `a double star crosses folders`() {
        assertTrue(matches("src/**/*.cs", "src/a.cs"))
        assertTrue(matches("src/**/*.cs", "src/x/y/a.cs"))
        assertTrue(matches("**/*.cs", "a.cs"))
        assertFalse(matches("src/**/*.cs", "other/a.cs"))
    }

    @Test
    fun `a trailing double star takes everything below`() {
        assertTrue(matches("src/Old/**", "src/Old/a.cs"))
        assertTrue(matches("src/Old/**", "src/Old/x/b.cs"))
    }

    @Test
    fun `a question mark is one character`() {
        assertTrue(matches("a?.cs", "ab.cs"))
        assertFalse(matches("a?.cs", "a.cs"))
        assertFalse(matches("a?.cs", "a/.cs"))
    }

    @Test
    fun `dots and other regex characters are literal`() {
        assertTrue(matches("a.b(1).cs", "a.b(1).cs"))
        assertFalse(matches("a.cs", "aXcs"))
        assertFalse(matches("a+b.cs", "aab.cs"))
    }

    @Test
    fun `backslashes and a leading dot slash are normalised`() {
        assertTrue(matches("src\\*.cs", "src/a.cs"))
        assertTrue(matches("./src/a.cs", "src/a.cs"))
    }
}
