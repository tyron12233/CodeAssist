package dev.codeassist.csharp.project

import dev.codeassist.csharp.newTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class CsMainsTest {

    private val dir = newTempDir()

    private fun write(name: String, text: String) {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.write(file, (text).toByteArray())
    }

    @Test
    fun `entry points across files, each class once`() {
        write("A.cs", "namespace N { class A { static void Main() {} } }")
        write("sub/B.cs", "class B { static int Main(string[] a) => 0; }\nclass C { }")
        write("D.cs", "namespace N { class A { static void Main() {} } }")
        assertEquals(listOf("N.A", "B"), CsMains.classes(dir))
    }

    @Test
    fun `a project with no entry point`() {
        write("Lib.cs", "class L { public static void Run() {} }")
        assertTrue(CsMains.classes(dir).isEmpty())
    }

    @Test
    fun `a changed file is read again, an unchanged one is not`() {
        write("A.cs", "class A { static void Main() {} }")
        assertEquals(listOf("A"), CsMains.classes(dir))
        val file = dir.resolve("A.cs")
        Files.write(file, ("class Renamed { static void Main() {} }").toByteArray())
        // The same size and a time the cache has not seen: written into the future so the change is visible.
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertEquals(listOf("Renamed"), CsMains.classes(dir))
    }

    @Test
    fun `build output folders are not scanned`() {
        write("A.cs", "class A { static void Main() {} }")
        write("obj/Gen.cs", "class Gen { static void Main() {} }")
        assertEquals(listOf("A"), CsMains.classes(dir))
    }

    @Test
    fun `the entry is the setting, else the first class`() {
        write("A.cs", "class A { static void Main() {} }")
        write("B.cs", "class B { static void Main() {} }")
        assertEquals("A", CsMains.entry(dir, null))
        assertEquals("B", CsMains.entry(dir, "B"))
    }

    @Test
    fun `the compiler is told which class only when it would have to choose`() {
        write("A.cs", "class A { static void Main() {} }")
        assertEquals(null, CsMains.compilerMain(dir, null))
        assertEquals("A", CsMains.compilerMain(dir, "A"))
        write("B.cs", "class B { static void Main() {} }")
        assertEquals("A", CsMains.compilerMain(dir, null))
        assertEquals("B", CsMains.compilerMain(dir, "B"))
    }

    @Test
    fun `no entry point and nothing set`() {
        write("L.cs", "class L { }")
        assertEquals(null, CsMains.entry(dir, null))
        assertEquals(null, CsMains.compilerMain(dir, null))
    }

    @Test
    fun `a startup object in the csproj sets the entry, and the setting beats it`() {
        write("A.cs", "class A { static void Main() {} }")
        write("B.cs", "class B { static void Main() {} }")
        write("App.csproj", "<Project><PropertyGroup><StartupObject>B</StartupObject></PropertyGroup></Project>")
        assertEquals("B", CsMains.entry(dir, null))
        assertEquals("B", CsMains.compilerMain(dir, null))
        assertEquals("A", CsMains.entry(dir, "A"))
        assertEquals("A", CsMains.compilerMain(dir, "A"))
    }

    @Test
    fun `beside top-level statements a Main runs through its class row, with the top-level file left out`() {
        write("A.cs", "class A { static void Main() {} }")
        write("B.cs", "class B { static void Main() {} }")
        write("Program.cs", "System.Console.WriteLine(1);\nclass P { static void Main() {} }")
        assertTrue(CsMains.hasTopLevelStatements(dir))
        assertEquals(listOf(dir.resolve("Program.cs")), CsMains.topLevelFiles(dir))
        // Program.cs's own class is not offered: running a class leaves that file out.
        assertEquals(listOf("A", "B"), CsMains.classes(dir))
        assertTrue(CsMains.classRows(dir))
        // The module's row runs the top-level program unless Module Settings names a class.
        assertEquals(null, CsMains.entry(dir, null))
        assertEquals(null, CsMains.compilerMain(dir, null))
        assertEquals("B", CsMains.entry(dir, "B"))
        assertEquals("B", CsMains.compilerMain(dir, "B"))
    }

    @Test
    fun `one Main beside top-level statements still gets a class row`() {
        write("A.cs", "class A { static void Main() {} }")
        assertEquals(false, CsMains.classRows(dir))
        write("Program.cs", "System.Console.WriteLine(1);")
        assertTrue(CsMains.classRows(dir))
    }
}
