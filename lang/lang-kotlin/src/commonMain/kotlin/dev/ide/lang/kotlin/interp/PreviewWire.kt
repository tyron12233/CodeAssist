package dev.ide.lang.kotlin.interp

import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.ByteArrayDataWriter

/**
 * A lowered preview as one blob: its entry point, every lowered function it may call, and the source
 * classes. What a host with no JVM hands the preview pipeline it runs in `:jvm-vm`, which decodes it with
 * the JVM build of this same code.
 */
object PreviewWire {
    private const val MAGIC = 0x56505731 // "VPW1"

    class Decoded(val entry: ResolvedFunction, val program: Map<String, ResolvedFunction>, val classes: List<ResolvedClass>) {
        operator fun component1() = entry
        operator fun component2() = program
        operator fun component3() = classes
    }

    fun encode(entry: ResolvedFunction, program: Map<String, ResolvedFunction>, classes: List<ResolvedClass>): ByteArray {
        val out = ByteArrayDataWriter()
        out.writeInt(MAGIC)
        out.writeInt(ResolvedTreeCodec.FORMAT)
        ResolvedTreeCodec.Writer(out).run {
            function(entry)
            map(program) { function(it) }
            list(classes) { klass(it) }
        }
        return out.toByteArray()
    }

    fun decode(blob: ByteArray): Decoded {
        val input = ByteArrayDataReader(blob)
        require(input.readInt() == MAGIC) { "not a preview blob" }
        require(input.readInt() == ResolvedTreeCodec.FORMAT) { "preview blob written by another codec version" }
        val reader = ResolvedTreeCodec.Reader(input)
        val entry = reader.function()
        val program = reader.map { reader.function() }
        val classes = reader.list { reader.klass() }
        return Decoded(entry, program, classes)
    }
}
