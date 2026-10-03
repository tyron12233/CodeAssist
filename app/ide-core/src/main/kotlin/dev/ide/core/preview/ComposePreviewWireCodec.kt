package dev.ide.core.preview

import dev.ide.core.LoweredComposePreview
import dev.ide.core.LoweredPreviewParameter
import dev.ide.lang.kotlin.interp.JavaDataReader
import dev.ide.lang.kotlin.interp.JavaDataWriter
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.ResolvedTreeCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Compact binary wire codec for a [LoweredComposePreview] — the "biggest single work item" of the Compose-preview
 * process isolation (see `docs/compose-preview-isolation.md`, Phase 1). The lowered preview (`entry`/`program`/
 * `classes`/`parameter`) is pure data (the `ResolvedTree` model — sealed `RNode`/`Binding`/`ResolvedCallable`,
 * `KotlinType`, no PSI), so it serializes to a blob that crosses the `IComposePreviewSession` AIDL boundary; the
 * `:preview` process decodes it back to the exact types the interpreter already consumes — no re-lowering (which
 * would need the full Kotlin symbol service + classpath in `:preview`, the RAM we are isolating away).
 *
 * This is a thin envelope over [ResolvedTreeCodec] (lang-kotlin), which owns the exhaustive per-declaration
 * encoding — shared with the preview lowering disk cache. The format is versioned (magic + the codec's FORMAT);
 * both ends ship in one APK, so no cross-version decode ever happens on the wire.
 */
object ComposePreviewWireCodec {

    private const val MAGIC = 0x43505731 // "CPW1"

    fun encode(preview: LoweredComposePreview): ByteArray {
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeInt(MAGIC)
        d.writeInt(ResolvedTreeCodec.FORMAT)
        ResolvedTreeCodec.Writer(JavaDataWriter(d)).run {
            function(preview.entry)
            map(preview.program) { function(it) }
            list(preview.classes) { klass(it) }
            nullable(preview.parameter) { param ->
                str(param.providerSimpleName); strN(param.providerFqn)
                nullable(param.providerClass) { klass(it) }; int(param.limit)
            }
        }
        d.flush()
        return bos.toByteArray()
    }

    /**
     * Decoded declarations kept across the [decode]s of one live preview session. The renderer tells what an edit
     * changed by INSTANCE identity (an unchanged function keeps its instance, so only changed ones are forced to
     * re-run), but every decode builds fresh instances, which made every update re-run the whole preview. With a
     * memo, a declaration whose encoded bytes are identical to the previous update's reuses the previous instance.
     * Not thread-safe: one session's updates are decoded one at a time.
     */
    class DecodeMemo {
        internal var entry: Pair<ByteArray, ResolvedFunction>? = null
        internal var functions: Map<String, Pair<ByteArray, ResolvedFunction>> = emptyMap()
        internal var classes: Map<String, Pair<ByteArray, ResolvedClass>> = emptyMap()
    }

    fun decode(bytes: ByteArray, memo: DecodeMemo? = null): LoweredComposePreview {
        val input = PositionedInput(bytes)
        val d = DataInputStream(input)
        require(d.readInt() == MAGIC) { "bad Compose preview wire magic" }
        require(d.readInt() == ResolvedTreeCodec.FORMAT) { "bad Compose preview wire format" }
        return ResolvedTreeCodec.Reader(JavaDataReader(d)).run {
            // Decode one declaration, handing back the previous instance when its bytes are unchanged.
            fun <T> reuse(previous: Pair<ByteArray, T>?, read: () -> T): Pair<ByteArray, T> {
                val start = input.position
                val value = read()
                val slice = bytes.copyOfRange(start, input.position)
                return if (previous != null && previous.first.contentEquals(slice)) previous else slice to value
            }
            val entry = reuse(memo?.entry) { function() }
            val functions = LinkedHashMap<String, Pair<ByteArray, ResolvedFunction>>()
            repeat(int()) {
                val key = str()
                functions[key] = reuse(memo?.functions?.get(key)) { function() }
            }
            val classes = ArrayList<Pair<ByteArray, ResolvedClass>>()
            repeat(int()) {
                // Classes are a list, so the previous instance is found by FQN after decoding.
                val decoded = reuse<ResolvedClass>(null) { klass() }
                val previous = memo?.classes?.get(decoded.second.fqn)
                classes += if (previous != null && previous.first.contentEquals(decoded.first)) previous else decoded
            }
            val parameter = nullable {
                LoweredPreviewParameter(str(), strN(), nullable { klass() }, int())
            }
            memo?.let { m ->
                m.entry = entry
                m.functions = functions
                m.classes = classes.associateBy { it.second.fqn }
            }
            LoweredComposePreview(
                entry = entry.second,
                program = functions.mapValuesTo(LinkedHashMap()) { it.value.second },
                classes = classes.map { it.second },
                parameter = parameter,
            )
        }
    }

    /** A byte-array stream that exposes its read position (`DataInputStream` reads through without buffering). */
    private class PositionedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val position: Int get() = pos
    }
}
