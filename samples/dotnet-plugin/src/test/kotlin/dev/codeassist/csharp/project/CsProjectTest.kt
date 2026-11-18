package dev.codeassist.csharp.project

import dev.codeassist.csharp.newTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CsProjectTest {

    private val root: Path = newTempDir()

    private fun touch(relative: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(0))
        return path
    }

    @Test
    fun `sources are every cs file, sorted`() {
        val b = touch("src/b.cs")
        val a = touch("src/a.cs")
        val top = touch("Top.cs")
        assertEquals(listOf(top, a, b).sortedBy { it.toString() }, CsProject.sources(root))
    }

    @Test
    fun `build output and tooling folders are not sources`() {
        val kept = touch("src/Program.cs")
        touch("bin/Debug/net10.0/Generated.cs")
        touch("obj/Temp.cs")
        touch(".git/hooks/x.cs")
        touch(".hidden/y.cs")
        assertEquals(listOf(kept), CsProject.sources(root))
    }

    @Test
    fun `other files and hidden cs files are not sources`() {
        val kept = touch("Program.cs")
        touch("readme.txt")
        touch("App.csproj")
        touch(".Scratch.cs")
        touch("notes.cs.bak")
        assertEquals(listOf(kept), CsProject.sources(root))
    }

    @Test
    fun `a missing folder has no sources`() {
        assertTrue(CsProject.sources(root.resolve("missing")).isEmpty())
    }

    @Test
    fun `an assembly name keeps letters, digits and underscores`() {
        assertEquals("MyApp", CsProject.assemblyName("My App"))
        assertEquals("my_app2", CsProject.assemblyName("my_app2"))
        assertEquals("Mod", CsProject.assemblyName("Mod-"))
    }

    @Test
    fun `an assembly name never starts with a digit`() {
        assertEquals("App2go", CsProject.assemblyName("2go"))
    }

    @Test
    fun `a name with nothing usable becomes App`() {
        assertEquals("App", CsProject.assemblyName(""))
        assertEquals("App", CsProject.assemblyName("---"))
    }

    @Test
    fun `a walk does not go into bin and obj, at any depth`() {
        val kept = touch("Models/Person.cs")
        touch("bin/Debug/a.dll")
        touch("obj/project.assets.json")
        touch("Models/obj/Temp.cs")
        assertEquals(listOf(kept), CsProject.files(root, Int.MAX_VALUE, skipHidden = true))
    }

    @Test
    fun `hidden folders are walked only when asked`() {
        val visible = touch("a.cs")
        val hidden = touch(".config/b.cs")
        assertEquals(listOf(visible), CsProject.files(root, Int.MAX_VALUE, skipHidden = true))
        assertEquals(listOf(visible, hidden).sortedBy { it.toString() },
            CsProject.files(root, Int.MAX_VALUE, skipHidden = false).sortedBy { it.toString() })
    }

    @Test
    fun `a walk stops at its depth`() {
        touch("one.cs")
        touch("a/two.cs")
        touch("a/b/three.cs")
        assertEquals(2, CsProject.files(root, 2, skipHidden = true).size)
    }
}
