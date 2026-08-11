package dev.ide.core.preview

import dev.ide.core.LoweredComposePreview
import dev.ide.core.LoweredPreviewParameter
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
        ResolvedTreeCodec.Writer(d).run {
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

    fun decode(bytes: ByteArray): LoweredComposePreview {
        val d = DataInputStream(ByteArrayInputStream(bytes))
        require(d.readInt() == MAGIC) { "bad Compose preview wire magic" }
        require(d.readInt() == ResolvedTreeCodec.FORMAT) { "bad Compose preview wire format" }
        return ResolvedTreeCodec.Reader(d).run {
            LoweredComposePreview(
                entry = function(),
                program = map { function() },
                classes = list { klass() },
                parameter = nullable {
                    LoweredPreviewParameter(str(), strN(), nullable { klass() }, int())
                },
            )
        }
    }
}
