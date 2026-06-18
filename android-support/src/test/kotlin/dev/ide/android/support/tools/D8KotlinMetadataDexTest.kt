package dev.ide.android.support.tools

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.tasks.DexArchives
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-process D8/R8 (8.13.19) crashes rewriting Kotlin metadata newer than its bundled
 * `kotlin-metadata-jvm` understands ("Should never be called"), and that crash drops the dex output for the
 * whole invocation — the on-device Compose failure where `MainActivity` (Kotlin 2.4) was absent from the APK.
 * The fix strips `@kotlin.Metadata` from program classes before dexing so D8 never enters its metadata path.
 * These tests pin both halves: the strip removes the annotation (class otherwise intact + still dexable), and
 * dexing the stripped class no longer makes D8 touch Kotlin metadata at all.
 */
class D8KotlinMetadataDexTest {

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

    @Test
    fun stripRemovesKotlinMetadataButKeepsTheClass() {
        val original = facetBytes()
        assertTrue(hasKotlinMetadata(original), "fixture must be a Kotlin class carrying @Metadata")

        val stripped = DexArchives.strippedKotlinMetadata(original)
        assertFalse(hasKotlinMetadata(stripped), "strip must remove @kotlin.Metadata")
        // The class is otherwise intact: still a readable class with the same name.
        assertTrue(ClassReader(stripped).className == "dev/ide/android/support/AndroidFacet", "class must survive the strip")
        // A class with no @Metadata is returned byte-for-byte (no needless rewrite).
        assertTrue(DexArchives.strippedKotlinMetadata(stripped).contentEquals(stripped), "metadata-free class passes through unchanged")
    }

    @Test
    fun strippedClassDexesWithoutTouchingKotlinMetadata() {
        val tmp = Files.createTempDirectory("d8-kotlin-meta")
        try {
            val classRel = "dev/ide/android/support/AndroidFacet.class"
            val jar = tmp.resolve("input.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { jos ->
                jos.putNextEntry(JarEntry(classRel)); jos.write(DexArchives.strippedKotlinMetadata(facetBytes())); jos.closeEntry()
            }
            val androidJar = tmp.resolve("absent-android.jar")   // none needed for intermediate per-class dexing
            val outDir = tmp.resolve("dex")

            val r = D8InProcessDexer().dexArchive(listOf(jar), emptyList(), androidJar, minApi = 24, release = false, outDir = outDir, threads = 1)

            assertTrue(r.success, "dexArchive failed: ${r.log}")
            assertTrue(Files.isRegularFile(outDir.resolve("dev/ide/android/support/AndroidFacet.dex")), "stripped class produced no .dex: ${r.log}")
            assertFalse(
                r.log.any { it.contains("kotlin metadata", ignoreCase = true) || it.contains("Kotlin metadata") },
                "D8 still entered its Kotlin-metadata path for a stripped class — strip ineffective: ${r.log}",
            )
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
