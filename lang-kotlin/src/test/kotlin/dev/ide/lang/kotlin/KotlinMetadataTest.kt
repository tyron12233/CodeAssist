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
    fun decodedSupertypesCarryTheirTypeArguments() {
        // The decode used to keep only supertype classifier FQNs, erasing their type arguments. That broke
        // member inheritance through a generic supertype: `ProvidableCompositionLocal<T> : CompositionLocal<T>`
        // lost the `<T>`, so the inherited `current: T` never substituted to the receiver's argument — the
        // `LocalTextStyle.current.copy(fontSize = …)` Compose-preview failure. `ArrayDeque<E> : AbstractMutableList<E>`
        // is the stdlib analog: the supertype must carry its `E` argument.
        val decoded = assertNotNull(KotlinMetadata.decode(entryBytes("kotlin/collections/ArrayDeque.class"), null))
        val abstractList = decoded.supertypes.filterIsInstance<dev.ide.lang.kotlin.symbols.KotlinType>()
            .firstOrNull { it.qualifiedName == "kotlin.collections.AbstractMutableList" }
        assertNotNull(abstractList, "ArrayDeque's supertype list should include AbstractMutableList; got ${decoded.supertypeFqns}")
        assertTrue(abstractList.typeArguments.isNotEmpty(), "the supertype must carry its type argument (E), not be erased")
        assertTrue(
            (abstractList.typeArguments.first() as? dev.ide.lang.kotlin.symbols.KotlinType)?.isTypeParameter == true,
            "the argument should be the type parameter E; got ${abstractList.typeArguments}",
        )
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
