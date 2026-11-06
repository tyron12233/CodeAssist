package dev.ide.core.plugins

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLeftoversTest {
    private val tmp: Path = Files.createTempDirectory("plugin-leftovers")
    private val data: Path = Files.createDirectories(tmp.resolve("plugin-data"))
    private val private: Path = Files.createDirectories(tmp.resolve("plugin-private"))

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    private fun file(path: Path, bytes: Int): Path {
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(bytes))
        return path
    }

    @Test
    fun `only folders no installed plugin owns are offered, both roots merged under one id`() {
        file(data.resolve("dev.codeassist.ndk/toolchain.bin"), 100)
        file(data.resolve("kz.moon.dartpluginv1/cache.bin"), 30)
        file(private.resolve("kz.moon.dartpluginv1/sdk/x"), 20)
        file(data.resolve(".plugin-names.properties"), 5)

        val found = PluginLeftovers(listOf(data, private)).scan(installed = listOf("dev.codeassist.ndk"))

        assertEquals(listOf("kz.moon.dartpluginv1"), found.map { it.id })
        assertEquals(50L, found.single().bytes)
        assertEquals(2, found.single().paths.size)
    }

    @Test
    fun `ownership ignores case, since shared storage folds it`() {
        file(data.resolve("kz.codingOnTheMoon.codeassistcsharp/a"), 10)
        val found = PluginLeftovers(listOf(data)).scan(installed = listOf("kz.codingonthemoon.CodeAssistCSharp"))
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a legacy directory is claimed for its plugin, offered only once that plugin is gone`() {
        val legacy = file(tmp.resolve("files/dart-plugin/sdk/lib.dill"), 40).parent.parent
        val store = PluginLeftovers(listOf(data, private), mapOf("dev.codeassist.dart" to listOf(legacy)))

        assertTrue(store.scan(installed = listOf("dev.codeassist.dart")).isEmpty())
        val found = store.scan(installed = emptyList()).single()
        assertEquals("dev.codeassist.dart", found.id)
        assertEquals(40L, found.bytes)
    }

    @Test
    fun `sizes and deletes do not follow links, so a linked native library survives`() {
        val nativeLib = file(tmp.resolve("native/libdart.so"), 500)
        val dir = Files.createDirectories(private.resolve("dev.codeassist.dart/sdk/bin"))
        Files.createSymbolicLink(dir.resolve("dart"), nativeLib)
        file(dir.resolve("snapshot"), 7)
        // The legacy path, after the plugin moved its home: a link to the new one.
        val legacy = tmp.resolve("files/dart-plugin")
        Files.createDirectories(legacy.parent)
        Files.createSymbolicLink(legacy, private.resolve("dev.codeassist.dart"))
        val store = PluginLeftovers(listOf(data, private), mapOf("dev.codeassist.dart" to listOf(legacy)))

        val found = store.scan(installed = emptyList()).single()
        assertEquals(7L, found.bytes, "the linked library is not the plugin's data")

        assertEquals(7L, store.delete("dev.codeassist.dart", installed = emptyList()))
        assertFalse(Files.exists(private.resolve("dev.codeassist.dart"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(legacy, LinkOption.NOFOLLOW_LINKS), "the old path's link goes too")
        assertTrue(Files.isRegularFile(nativeLib), "a link's target is never deleted")
    }

    @Test
    fun `an installed plugin's data is never deleted, whatever id is asked for`() {
        val kept = file(data.resolve("dev.codeassist.ndk/toolchain.bin"), 100)
        assertEquals(0L, PluginLeftovers(listOf(data)).delete("dev.codeassist.ndk", installed = listOf("dev.codeassist.ndk")))
        assertTrue(Files.exists(kept))
    }

    @Test
    fun `names recorded while installed label the leftover later`() {
        file(data.resolve("dev.codeassist.dart/x"), 1)
        val store = PluginLeftovers(listOf(data))
        store.remember(mapOf("dev.codeassist.dart" to "Dart & Flutter"))
        assertEquals("Dart & Flutter", store.scan(installed = emptyList()).single().name)
    }
}
