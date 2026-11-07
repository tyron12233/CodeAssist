package dev.ide.core.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which of a plugin's packaged native files are programs. The fixtures are the first 4 KB of real Android
 * binaries: aapt2 as the IDE bundles it (static), the Dart VM the Dart plugin runs (PIE), a Dart AOT snapshot
 * (an ELF shared object the VM maps, never runs) and AndroidX's JNI library in both 64- and 32-bit ARM.
 */
class PluginExecutablesTest {

    private fun head(name: String): ByteArray =
        javaClass.getResourceAsStream("/plugin-executables/$name.head")!!.use { it.readBytes() }

    @Test
    fun programsAreTold() {
        assertTrue(PluginExecutables.isExecutable(head("static-exe")), "a static ET_EXEC")
        assertTrue(PluginExecutables.isExecutable(head("pie-exe")), "a PIE names /system/bin/linker64")
    }

    @Test
    fun librariesAreNot() {
        assertFalse(PluginExecutables.isExecutable(head("jni-lib")))
        assertFalse(PluginExecutables.isExecutable(head("jni-lib-32")))
        assertFalse(PluginExecutables.isExecutable(head("aot-snapshot")), "an ELF the VM loads, not one it runs")
        assertFalse(PluginExecutables.isExecutable("not an ELF file at all, just text in a .so".toByteArray()))
        assertFalse(PluginExecutables.isExecutable(ByteArray(0)))
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): Path {
        val file = Files.createTempFile("plugin", ".apk")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex")); zip.write(byteArrayOf(1)); zip.closeEntry()
            for ((name, bytes) in entries) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }

    @Test
    fun aBuildWithAProgramForThisDeviceNeedsAnInstalledHome() {
        val apk = apk(
            "lib/arm64-v8a/libcsc.so" to head("pie-exe"),
            "lib/arm64-v8a/libjni.so" to head("jni-lib"),
            "lib/x86_64/libcsc.so" to head("pie-exe"),
        )
        val scan = assertNotNull(PluginExecutables.scan(apk, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm64-v8a", scan.abi)
        assertEquals(listOf("lib/arm64-v8a/libcsc.so"), scan.executables)
        assertEquals(listOf("lib/arm64-v8a/libcsc.so", "lib/arm64-v8a/libjni.so"), scan.entries, "the JNI library goes along")
    }

    @Test
    fun librariesOnlyOrNothingForThisDeviceNeedNone() {
        assertNull(PluginExecutables.scan(apk("lib/arm64-v8a/libjni.so" to head("jni-lib")), listOf("arm64-v8a")))
        assertNull(PluginExecutables.scan(apk("lib/x86_64/libcsc.so" to head("pie-exe")), listOf("arm64-v8a")))
        assertNull(PluginExecutables.scan(apk(), listOf("arm64-v8a")))
    }

    @Test
    fun theFingerprintFollowsTheLibrariesOnly() {
        val a = PluginExecutables.scan(apk("lib/arm64-v8a/libcsc.so" to head("pie-exe")), listOf("arm64-v8a"))!!
        val same = PluginExecutables.scan(apk("lib/arm64-v8a/libcsc.so" to head("pie-exe")), listOf("arm64-v8a"))!!
        val changed = PluginExecutables.scan(apk("lib/arm64-v8a/libcsc.so" to head("pie-exe") + byteArrayOf(0)), listOf("arm64-v8a"))!!
        assertEquals(a.fingerprint, same.fingerprint, "a rebuild with the same libraries needs no new install")
        assertNotEquals(a.fingerprint, changed.fingerprint)
    }
}
