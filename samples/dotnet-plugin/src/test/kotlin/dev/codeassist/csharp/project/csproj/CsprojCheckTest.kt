package dev.codeassist.csharp.project.csproj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsprojCheckTest {

    private fun env(
        mains: List<String> = emptyList(),
        top: Boolean = false,
        exists: Set<String> = emptySet(),
    ) = CsprojCheck.Env({ it in exists }, mains, top)

    private fun check(body: String, env: CsprojCheck.Env = env()): List<CsprojCheck.Finding> =
        CsprojCheck.check("<Project Sdk=\"Microsoft.NET.Sdk\">\n$body\n</Project>", env)

    private fun messages(body: String, env: CsprojCheck.Env = env()) = check(body, env).map { it.message }

    @Test
    fun cleanProjectHasNoFindings() {
        val body = """
            <PropertyGroup>
              <OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework>
              <Nullable>enable</Nullable><LangVersion>latest</LangVersion><WarningLevel>4</WarningLevel>
            </PropertyGroup>
            <ItemGroup><PackageReference Include="Foo" Version="1.0" /></ItemGroup>
        """.trimIndent()
        assertEquals(emptyList<String>(), messages(body))
    }

    @Test
    fun brokenXmlIsOneErrorOnItsLine() {
        val found = CsprojCheck.check("<Project>\n  <PropertyGroup>\n</Project>", env())
        assertEquals(1, found.size)
        assertEquals(CsprojCheck.Level.ERROR, found[0].level)
        assertEquals("csproj.xml", found[0].code)
    }

    @Test
    fun badEnumAndBooleanValues() {
        val found = messages(
            "<PropertyGroup><OutputType>Exee</OutputType><Optimize>yes</Optimize><Nullable>on</Nullable>" +
                "</PropertyGroup>",
        )
        assertEquals(3, found.size)
        assertTrue(found.any { it.startsWith("OutputType") })
    }

    @Test
    fun expressionsAreNotJudged() {
        assertEquals(emptyList<String>(), messages("<PropertyGroup><Optimize>$(Fast)</Optimize></PropertyGroup>"))
    }

    @Test
    fun valueRangeCoversOnlyTheValue() {
        val text = "<Project><PropertyGroup><OutputType> Exee </OutputType></PropertyGroup></Project>"
        val f = CsprojCheck.check(text, env()).single()
        assertEquals("Exee", text.substring(f.start, f.end))
    }

    @Test
    fun langVersionAndWarningLevel() {
        assertEquals(
            2,
            check("<PropertyGroup><LangVersion>x1</LangVersion><WarningLevel>12</WarningLevel></PropertyGroup>").size,
        )
        assertEquals(0, check("<PropertyGroup><LangVersion>12.0</LangVersion></PropertyGroup>").size)
    }

    @Test
    fun packageProblems() {
        val body = """
            <ItemGroup>
              <PackageReference Include="A" />
              <PackageReference Include="a" Version="1" />
              <PackageReference Version="1" />
            </ItemGroup>
        """.trimIndent()
        val found = messages(body)
        assertTrue(found.any { it.startsWith("No Version") })
        assertTrue(found.any { it.contains("more than once") })
        assertTrue(found.any { it.contains("needs an Include") })
    }

    @Test
    fun packageVersionAsChildElementIsAVersion() {
        val body = "<ItemGroup><PackageReference Include=\"A\"><Version>1.0</Version></PackageReference></ItemGroup>"
        assertEquals(emptyList<String>(), messages(body))
    }

    @Test
    fun projectReferenceIsNotSupported() {
        assertEquals(
            listOf("Project references are not supported yet"),
            messages("<ItemGroup><ProjectReference Include=\"../Lib/Lib.csproj\" /></ItemGroup>"),
        )
    }

    @Test
    fun missingHintPathAndCompileFile() {
        val body = """
            <ItemGroup>
              <Reference Include="X" HintPath="libs/x.dll" />
              <Compile Include="Gone.cs" />
              <Compile Include="src/**/*.cs" />
              <Compile Include="Here.cs" />
            </ItemGroup>
        """.trimIndent()
        val found = messages(body, env(exists = setOf("Here.cs")))
        assertEquals(listOf("HintPath not found: libs/x.dll", "File not found: Gone.cs"), found)
    }

    @Test
    fun startupObjectAgainstTheProject() {
        val body = "<PropertyGroup><StartupObject>Nope</StartupObject></PropertyGroup>"
        assertTrue(messages(body, env(mains = listOf("A", "B"))).single().startsWith("No static Main in 'Nope'"))
        assertEquals(emptyList<String>(), messages(body, env(mains = listOf("Nope"))))
        assertTrue(messages(body, env(top = true)).single().contains("ignored"))
        assertEquals(emptyList<String>(), messages(body, env()))
    }
}
