package dev.ide.android.support.resources

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves [FrameworkResourceScanner] reads framework resource names from `android.R$*` (synthetic android.jar). */
class FrameworkResourceScannerTest {

    /** A minimal `android/R$<type>` class with the given public-static-int field names. */
    private fun rClass(type: String, fields: List<String>): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "android/R\$$type", null, "java/lang/Object", null)
        for (f in fields) cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, f, "I", null, 0).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun readsFrameworkResourceNamesByType() {
        val tmp = Files.createTempDirectory("fwres")
        val jar = jar(tmp, mapOf(
            "android/R\$string" to rClass("string", listOf("ok", "cancel", "yes")),
            "android/R\$color" to rClass("color", listOf("holo_blue_dark", "white")),
            // A non-resource class is ignored.
            "android/view/View" to rClass("ignored", emptyList()),
        ))

        val scanned = FrameworkResourceScanner.scan(jar)
        assertEquals(listOf("cancel", "ok", "yes"), scanned[ResourceType.STRING], "sorted string names")
        assertTrue(scanned[ResourceType.COLOR]?.containsAll(listOf("holo_blue_dark", "white")) == true)
        assertTrue(scanned[ResourceType.LAYOUT].isNullOrEmpty(), "no R\$layout class → no layout names")
    }

    private fun jar(dir: Path, classes: Map<String, ByteArray>): Path {
        val jar = dir.resolve("android.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((internal, bytes) in classes) {
                zip.putNextEntry(ZipEntry("$internal.class")); zip.write(bytes); zip.closeEntry()
            }
        }
        return jar
    }
}
