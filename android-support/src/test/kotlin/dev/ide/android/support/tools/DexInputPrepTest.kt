package dev.ide.android.support.tools

import dev.ide.android.support.AndroidFacet
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DexInputPrep.stripKotlinMetadata] prepares the preview library jars for the bundled in-process D8: a Kotlin
 * jar (material-icons-extended, Compose libs) is replaced by a `@kotlin.Metadata`-stripped copy so D8 doesn't
 * drop the invocation; a pure-Java jar passes through unchanged.
 */
class DexInputPrepTest {

    private fun facetBytes(): ByteArray =
        AndroidFacet::class.java.classLoader.getResourceAsStream("dev/ide/android/support/AndroidFacet.class")!!.readBytes()

    private fun hasKotlinMetadata(bytes: ByteArray): Boolean {
        var found = false
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean) =
                null.also { if (descriptor == "Lkotlin/Metadata;") found = true }
        }, ClassReader.SKIP_CODE)
        return found
    }

    private fun classBytes(jar: Path, entry: String): ByteArray =
        ZipFile(jar.toFile()).use { zf -> zf.getInputStream(zf.getEntry(entry)).use { it.readBytes() } }

    @Test
    fun stripsKotlinJarsAndPassesJavaJarsThrough() {
        val tmp = Files.createTempDirectory("dexinputprep")
        try {
            assertTrue(hasKotlinMetadata(facetBytes()), "fixture must be a Kotlin class with @Metadata")
            val kotlinJar = tmp.resolve("kotlin-lib.jar")
            JarOutputStream(Files.newOutputStream(kotlinJar)).use { jos ->
                jos.putNextEntry(JarEntry("dev/ide/android/support/AndroidFacet.class")); jos.write(facetBytes()); jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/kotlin-lib.kotlin_module")); jos.write(byteArrayOf(1)); jos.closeEntry()
            }
            val javaJar = tmp.resolve("java-lib.jar")
            JarOutputStream(Files.newOutputStream(javaJar)).use { jos ->
                jos.putNextEntry(JarEntry("com/example/Foo.class")); jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte())); jos.closeEntry()
            }

            val out = DexInputPrep.stripKotlinMetadata(listOf(kotlinJar, javaJar), tmp.resolve("work"))

            assertTrue(out[0] != kotlinJar, "the Kotlin jar is replaced by a stripped copy")
            assertFalse(hasKotlinMetadata(classBytes(out[0], "dev/ide/android/support/AndroidFacet.class")), "@Metadata stripped from the Kotlin jar's class")
            assertSame(javaJar, out[1], "a pure-Java jar (no .kotlin_module) passes through unchanged")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
