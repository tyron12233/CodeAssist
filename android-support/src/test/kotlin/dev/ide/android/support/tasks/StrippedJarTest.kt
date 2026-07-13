package dev.ide.android.support.tasks

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DexArchives.strippedJar] — the library-side counterpart to the project-class strip: it drops
 * `@kotlin.Metadata` from every class of a Kotlin *library* jar before D8 sees it, so the bundled D8's older
 * metadata parser never warns (or hits the rewriter drop path). A pure-Java jar (no `.kotlin_module`) must be
 * returned untouched so it is not needlessly re-jarred.
 */
class StrippedJarTest {

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

    private fun entriesOf(jar: Path): Map<String, ByteArray> =
        ZipFile(jar.toFile()).use { zf ->
            zf.entries().asSequence().filter { !it.isDirectory }
                .associate { it.name to zf.getInputStream(it).use { s -> s.readBytes() } }
        }

    @Test
    fun stripsMetadataFromAKotlinLibraryJarAndCopiesEverythingElse() {
        val tmp = Files.createTempDirectory("stripped-jar")
        try {
            val src = tmp.resolve("kotlin-lib.jar")
            JarOutputStream(Files.newOutputStream(src)).use { jos ->
                jos.putNextEntry(JarEntry("dev/ide/android/support/AndroidFacet.class")); jos.write(facetBytes()); jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/kotlin-lib.kotlin_module")); jos.write(byteArrayOf(9, 9)); jos.closeEntry()
                jos.putNextEntry(JarEntry("META-INF/services/some.Service")); jos.write("impl".toByteArray()); jos.closeEntry()
            }
            assertTrue(hasKotlinMetadata(facetBytes()), "fixture must be a Kotlin class carrying @Metadata")

            val dst = tmp.resolve("stripped.jar")
            val out = DexArchives.strippedJar(src, dst)
            assertEquals(dst, out, "a Kotlin jar (has .kotlin_module) is rewritten to the destination path")

            val entries = entriesOf(out)
            assertFalse(hasKotlinMetadata(entries.getValue("dev/ide/android/support/AndroidFacet.class")), "@Metadata must be stripped from the library class")
            assertEquals("dev/ide/android/support/AndroidFacet", ClassReader(entries.getValue("dev/ide/android/support/AndroidFacet.class")).className, "class survives the strip")
            // Non-class entries copied byte-for-byte (services, the kotlin_module marker, etc.).
            assertTrue(entries.containsKey("META-INF/kotlin-lib.kotlin_module"), "non-class entries are preserved")
            assertEquals("impl", entries.getValue("META-INF/services/some.Service").decodeToString(), "resource entries copied byte-for-byte")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun pureJavaJarWithoutKotlinModuleIsReturnedUnchanged() {
        val tmp = Files.createTempDirectory("stripped-jar-java")
        try {
            val src = tmp.resolve("java-lib.jar")
            JarOutputStream(Files.newOutputStream(src)).use { jos ->
                jos.putNextEntry(JarEntry("com/example/Foo.class")); jos.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte())); jos.closeEntry()
            }
            val dst = tmp.resolve("should-not-exist.jar")
            val out = DexArchives.strippedJar(src, dst)
            assertSame(src, out, "a jar with no .kotlin_module is returned as-is (never re-jarred)")
            assertFalse(Files.exists(dst), "no destination jar is written for a pure-Java library")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
