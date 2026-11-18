package dev.codeassist.csharp.project.nuget

import dev.codeassist.csharp.newTempDir
import dev.codeassist.csharp.project.csproj.Csproj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NuGetTest {

    private val root: Path = newTempDir()
    private val base = "https://flat.test/v3-flatcontainer/"

    /** The fake nuget.org: answers from [files] and remembers every URL asked for. */
    private class Server : NuGet.Fetch {
        val files = HashMap<String, ByteArray>()
        val asked = ArrayList<String>()
        override fun get(url: String): ByteArray? {
            asked += url
            return files[url]
        }

        fun downloads() = asked.count { it.endsWith(".nupkg") }
    }

    private val server = Server().also {
        it.files["https://api.nuget.org/v3/index.json"] = (
            """{"version":"3.0.0","resources":[""" +
                """{"@id":"https://flat.test/search","@type":"SearchQueryService"},""" +
                """{"@id":"${base}","@type":"PackageBaseAddress/3.0.0","comment":"base"}]}"""
            ).toByteArray()
    }

    private fun nuget() = NuGet(root, server)

    private fun nuspec(id: String, version: String, dependencies: String = ""): String =
        """<?xml version="1.0"?><package xmlns="http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd">""" +
            "<metadata><id>$id</id><version>$version</version>$dependencies</metadata></package>"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Adds a package to the server: its `.nupkg`, with a `.nuspec` and one DLL in each of [frameworks]. */
    private fun publish(
        id: String,
        version: String,
        frameworks: List<String> = listOf("netstandard2.0"),
        dependencies: String = "",
        dll: String = id,
        docs: Boolean = false,
    ) {
        val lower = id.lowercase()
        val entries = ArrayList<Pair<String, String>>()
        entries += "$id.nuspec" to nuspec(id, version, dependencies)
        for (framework in frameworks) {
            entries += "lib/$framework/$dll.dll" to "$id $version for $framework"
            if (docs) entries += "lib/$framework/$dll.xml" to "docs of $id for $framework"
        }
        server.files["$base$lower/$version/$lower.$version.nupkg"] = zip(*entries.toTypedArray())
    }

    private fun versions(id: String, vararg list: String) {
        server.files["$base${id.lowercase()}/index.json"] =
            """{"versions":[${list.joinToString(",") { "\"$it\"" }}]}""".toByteArray()
    }

    private fun names(result: NuGet.Result) = result.dlls.map { it.fileName.toString() }

    private fun read(path: Path) = String(Files.readAllBytes(path))

    @Test
    fun `a package is downloaded and its dll is given`() {
        publish("Newtonsoft.Json", "13.0.3")
        val result = nuget().restore(listOf(NuGet.Request("Newtonsoft.Json", "13.0.3")), emptySet())
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(listOf("Newtonsoft.Json.dll"), names(result))
        assertTrue(read(result.dlls.single()).contains("13.0.3"))
    }

    @Test
    fun `the base address comes from the service index`() {
        publish("A", "1.0.0")
        nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet())
        assertTrue(server.asked.contains("${base}a/1.0.0/a.1.0.0.nupkg"))
    }

    @Test
    fun `only the best framework's dlls are kept`() {
        publish("A", "1.0.0", frameworks = listOf("netstandard2.0", "net8.0", "net472"))
        val result = nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet())
        assertEquals(1, result.dlls.size)
        assertTrue(read(result.dlls.single()).endsWith("for net8.0"))
    }

    @Test
    fun `the documentation beside the best framework's dll is kept too`() {
        publish("A", "1.0.0", frameworks = listOf("netstandard2.0", "net8.0"), docs = true)
        val dll = nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet()).dlls.single()
        assertEquals("docs of A for net8.0", read(dll.resolveSibling("A.xml")))
    }

    @Test
    fun `a package without documentation is still unpacked`() {
        publish("A", "1.0.0")
        val dll = nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet()).dlls.single()
        assertTrue(!Files.exists(dll.resolveSibling("A.xml")))
    }

    @Test
    fun `a restored package is not downloaded again`() {
        publish("A", "1.0.0")
        val requests = listOf(NuGet.Request("A", "1.0.0"))
        nuget().restore(requests, emptySet())
        assertEquals(1, server.downloads())
        // A new object, as after a restart: the cache is on disk.
        val again = nuget().restore(requests, emptySet())
        assertEquals(1, server.downloads())
        assertEquals(listOf("A.dll"), names(again))
    }

    @Test
    fun `cached never uses the network and leaves out what is not unpacked`() {
        publish("A", "1.0.0")
        val before = nuget().cached(listOf(NuGet.Request("A", "1.0.0")), emptySet())
        assertEquals(emptyList<Path>(), before.dlls)
        assertEquals(emptyList<String>(), server.asked)
        nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet())
        server.asked.clear()
        val after = nuget().cached(listOf(NuGet.Request("A", "1.0.0")), emptySet())
        assertEquals(listOf("A.dll"), names(after))
        assertEquals(emptyList<String>(), server.asked)
    }

    @Test
    fun `isRestored is false until the package is unpacked`() {
        publish("A", "1.0.0")
        val requests = listOf(NuGet.Request("A", "1.0.0"))
        assertFalse(nuget().isRestored(requests, emptySet()))
        nuget().restore(requests, emptySet())
        assertTrue(nuget().isRestored(requests, emptySet()))
    }

    @Test
    fun `dependencies are restored too, from the best framework group`() {
        publish(
            "Top", "1.0.0",
            dependencies = """<dependencies>
                <group targetFramework=".NETFramework4.5"><dependency id="Old" version="1.0.0" /></group>
                <group targetFramework=".NETStandard2.0"><dependency id="Dep" version="[2.0.0, 3.0.0)" /></group>
            </dependencies>""",
        )
        publish("Dep", "2.0.0")
        publish("Old", "1.0.0")
        val result = nuget().restore(listOf(NuGet.Request("Top", "1.0.0")), emptySet())
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(listOf("Dep.dll", "Top.dll"), names(result))
    }

    @Test
    fun `the highest requested version of a package is the one used`() {
        publish("Shared", "1.0.0")
        publish("Shared", "2.0.0")
        publish(
            "Top", "1.0.0",
            dependencies = """<dependencies><dependency id="Shared" version="2.0.0" /></dependencies>""",
        )
        val result = nuget().restore(
            listOf(NuGet.Request("Shared", "1.0.0"), NuGet.Request("Top", "1.0.0")),
            emptySet(),
        )
        val shared = result.dlls.single { it.fileName.toString() == "Shared.dll" }
        assertTrue(read(shared).contains("2.0.0"))
        assertEquals(2, result.dlls.size)
    }

    @Test
    fun `a package the framework provides is neither downloaded nor given`() {
        publish(
            "Top", "1.0.0",
            dependencies = """<dependencies><dependency id="System.Memory" version="4.5.5" /></dependencies>""",
        )
        publish("System.Memory", "4.5.5")
        val result = nuget().restore(listOf(NuGet.Request("Top", "1.0.0")), setOf("system.memory.dll"))
        assertEquals(listOf("Top.dll"), names(result))
        assertEquals(1, server.downloads())
    }

    @Test
    fun `the library of the netstandard meta package is never asked for`() {
        publish(
            "Top", "1.0.0",
            dependencies = """<dependencies><dependency id="NETStandard.Library" version="2.0.3" /></dependencies>""",
        )
        nuget().restore(listOf(NuGet.Request("Top", "1.0.0")), emptySet())
        assertEquals(1, server.downloads())
    }

    @Test
    fun `a package that does not exist is a problem`() {
        val result = nuget().restore(listOf(NuGet.Request("Nope", "1.0.0")), emptySet())
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().contains("Nope"))
        assertEquals(emptyList<Path>(), result.dlls)
    }

    @Test
    fun `a package with no usable framework is a problem`() {
        publish("Old", "1.0.0", frameworks = listOf("net45", "net8.0-android"))
        val result = nuget().restore(listOf(NuGet.Request("Old", "1.0.0")), emptySet())
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().contains("no library"))
        assertFalse(nuget().isRestored(listOf(NuGet.Request("Old", "1.0.0")), emptySet()))
    }

    @Test
    fun `a floating version takes the newest stable one`() {
        versions("Fl", "1.0.0", "1.2.0", "1.3.0-beta", "2.0.0")
        publish("Fl", "1.2.0")
        val result = nuget().restore(listOf(NuGet.Request("Fl", "1.*")), emptySet())
        assertEquals(emptyList<String>(), result.problems)
        assertTrue(read(result.dlls.single()).contains("1.2.0"))
    }

    @Test
    fun `a missing version means the newest stable one`() {
        versions("Fl", "1.0.0", "2.0.0", "3.0.0-rc.1")
        publish("Fl", "2.0.0")
        val result = nuget().restore(listOf(NuGet.Request("Fl", null)), emptySet())
        assertTrue(read(result.dlls.single()).contains("2.0.0"))
    }

    @Test
    fun `a floating version offline falls back to what is cached`() {
        versions("Fl", "1.0.0", "1.1.0")
        publish("Fl", "1.1.0")
        nuget().restore(listOf(NuGet.Request("Fl", "1.*")), emptySet())
        val cached = nuget().cached(listOf(NuGet.Request("Fl", "1.*")), emptySet())
        assertTrue(read(cached.dlls.single()).contains("1.1.0"))
    }

    @Test
    fun `cancelling stops before the next package`() {
        publish("A", "1.0.0")
        val result = nuget().restore(listOf(NuGet.Request("A", "1.0.0")), emptySet()) { true }
        assertEquals(0, server.downloads())
        assertEquals(emptyList<Path>(), result.dlls)
    }

    @Test
    fun `version numbers are normalized in the url`() {
        publish("A", "1.0.0")
        nuget().restore(listOf(NuGet.Request("A", "1.0")), emptySet())
        assertTrue(server.asked.contains("${base}a/1.0.0/a.1.0.0.nupkg"))
    }

    @Test
    fun `dependencies without groups are read`() {
        val spec = nuspec(
            "X", "1.0.0",
            """<dependencies><dependency id="A" version="1.0" /><dependency id="B" /></dependencies>""",
        )
        assertEquals(listOf("A" to "1.0", "B" to null), NuGet.dependenciesOf(spec))
    }

    @Test
    fun `a nuspec with no dependencies has none`() {
        assertEquals(emptyList<Pair<String, String?>>(), NuGet.dependenciesOf(nuspec("X", "1.0.0")))
    }

    @Test
    fun `a group for no framework is used when none fits`() {
        val spec = nuspec(
            "X", "1.0.0",
            """<dependencies><group targetFramework=".NETFramework4.5"><dependency id="Old" /></group>""" +
                """<group><dependency id="Any" version="1.0" /></group></dependencies>""",
        )
        assertEquals(listOf("Any" to "1.0"), NuGet.dependenciesOf(spec))
    }

    @Test
    fun `a Csproj reads its packages into requests`() {
        val file = root.resolve("App.csproj")
        val text = "<Project><ItemGroup><PackageReference Include=\"A\" Version=\"1.0\"/></ItemGroup></Project>"
        Files.write(file, text.toByteArray())
        val info = Csproj.read(file)
        val requests = Csproj.packageRequests(info)
        assertEquals("A", requests.single().id)
        assertEquals("1.0", requests.single().version)
    }
}
