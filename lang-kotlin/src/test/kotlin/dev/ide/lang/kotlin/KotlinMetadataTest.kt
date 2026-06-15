package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.KotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the `@kotlin.Metadata` decode path against the REAL kotlin-stdlib jar on the test
 * classpath: ASM annotation extraction -> kotlin-metadata-jvm -> neutral symbols. Covers two shapes:
 * a regular Kotlin class (members) and the stdlib extension facades (`string.trim`, `list.map`).
 */
class KotlinMetadataTest {

    private fun stdlibJarPath(): String {
        val cp = System.getProperty("java.class.path").split(File.pathSeparator)
        return cp.firstOrNull { entry ->
            entry.endsWith(".jar") && runCatching {
                ZipFile(entry).use { it.getEntry("kotlin/Pair.class") != null }
            }.getOrDefault(false)
        } ?: error("kotlin-stdlib jar not found on test classpath")
    }

    private fun entryBytes(name: String): ByteArray =
        ZipFile(stdlibJarPath()).use { z -> z.getInputStream(z.getEntry(name)).use { it.readBytes() } }

    private fun allExtensions(): List<KotlinSymbol> =
        ZipFile(stdlibJarPath()).use { z ->
            z.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .flatMap { e ->
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    KotlinMetadata.decode(bytes, null)?.extensions ?: emptyList()
                }
                .toList()
        }

    @Test
    fun decodesKotlinClassMembers() {
        val decoded = KotlinMetadata.decode(entryBytes("kotlin/Pair.class"), null)
        assertNotNull(decoded, "kotlin.Pair should decode as a Kotlin class")
        assertTrue(decoded.classFqn == "kotlin.Pair", "fqn was ${decoded.classFqn}")
        val names = decoded.ownMembers.map { it.name }
        assertTrue("first" in names && "second" in names, "properties first/second; got $names")
        assertTrue("component1" in names && "copy" in names, "functions component1/copy; got $names")
        assertTrue(decoded.supertypeFqns.isNotEmpty(), "Pair has at least kotlin.Any as a supertype")
    }

    @Test
    fun decodesStdlibExtensions() {
        val exts = allExtensions()
        assertTrue(exts.size > 500, "stdlib should yield many extensions; got ${exts.size}")
        val trim = exts.firstOrNull { it.name == "trim" && it.receiverTypeFqn?.contains("CharSequence") == true }
        assertNotNull(trim, "String/CharSequence.trim extension expected")
        val map = exts.firstOrNull { it.name == "map" && it.receiverTypeFqn == "kotlin.collections.Iterable" }
        assertNotNull(map, "Iterable.map extension expected (got receivers: ${exts.filter { it.name == "map" }.map { it.receiverTypeFqn }.distinct()})")
        // Extensions must carry a receiver FQN — that's what the extension index keys on.
        assertTrue(exts.all { it.receiverTypeFqn != null })
    }
}
