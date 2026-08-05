package dev.ide.lang.kotlin.index

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.platform.ContentHash
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three `kotlin.*` binary indexes (`kotlin.typeShape`, `kotlin.callables`, `kotlin.pkgDecls`) each need the
 * class's decoded `@kotlin.Metadata`. They must share ONE `KotlinMetadata.decode` (via [sharedMetadata]), not
 * decode the class three times — the redundant-decode-per-class cold-build cost, most of which is `android.jar`
 * (~40k PLAIN Java classes, each decoding to null: the dominant case is exactly this "no metadata" one).
 */
class BinaryDecodeSharingTest {

    /** Mirrors the real `LibraryInput` (memoized `bytes()` + a per-input `shared` memo) but records how many
     *  times each `shared` key's compute actually runs. */
    private class RecordingInput(
        override val origin: IndexOrigin,
        override val unitName: String?,
        private val b: ByteArray,
    ) : IndexInput {
        override val contentHash = ContentHash.of(b)
        override val sourcePath: Path? = null
        var bytesCalls = 0
            private set
        val computeRuns = HashMap<String, Int>()
        override fun bytes(): ByteArray { bytesCalls++; return b }
        override fun text(): String? = null
        override fun dom() = null

        private val memo = HashMap<String, Any?>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> shared(key: String, compute: () -> T): T {
            if (memo.containsKey(key)) return memo[key] as T
            computeRuns[key] = (computeRuns[key] ?: 0) + 1
            val v = compute(); memo[key] = v; return v
        }
    }

    /** A plain `public class p/Foo` in bytecode — no `@kotlin.Metadata` (the `android.jar` shape). */
    private fun plainClassBytes(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "p/Foo", null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun binaryIndexesShareOneMetadataDecodePerClass() {
        val input = RecordingInput(IndexOrigin.LIBRARY, "p/Foo.class", plainClassBytes())

        // typeShape still indexes the plain class (via its JavaBytecode fallback); callables/pkgDecls yield
        // nothing for a non-Kotlin class — but all three route their decode through the shared memo.
        assertTrue(KotlinTypeShapeIndex.index(input).isNotEmpty(), "typeShape indexes the plain type")
        KotlinCallableIndex.index(input)
        KotlinPackageDeclIndex.index(input)

        assertEquals(1, input.computeRuns["kotlin.metadata"], "one shared @Metadata decode per class")
    }

    @Test
    fun binaryIndexesShareOneClassReaderPerClass() {
        val input = RecordingInput(IndexOrigin.LIBRARY, "p/Foo.class", plainClassBytes())

        // The five `kotlin.*` binary indexes each need the parsed class. Before the shared-reader change a plain
        // Java class (the android.jar shape) was fed to a fresh ASM ClassReader by typeShape (facade check +
        // @Metadata decode + JavaBytecode fallback), subtype.binary, and annotation.binary — several constant-pool
        // parses of the same bytes. They now all run over ONE reader cached under IndexInput.CLASS_READER.
        KotlinTypeShapeIndex.index(input)
        BinarySubtypeIndex.index(input)
        BinaryAnnotationIndex.index(input)
        KotlinCallableIndex.index(input)
        KotlinPackageDeclIndex.index(input)

        assertEquals(1, input.computeRuns[IndexInput.CLASS_READER], "one shared ClassReader per class")
    }
}
