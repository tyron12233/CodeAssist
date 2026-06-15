package dev.ide.index

import java.io.DataInput
import java.io.DataOutput

/**
 * Value payloads for the v1 indexes, shared by their producers (index-impl, lang-jdt) and consumers
 * (resolution/completion). Each has a matching [Externalizer] for the on-disk per-artifact cache.
 */

/** classNames hit: the FQN, where it came from, and its kind (drives the completion badge + auto-import). */
data class ClassNameValue(val fqn: String, val origin: IndexOrigin, val kind: String)

/** go-to-symbol hit: declaration name, kind, the source file, and the offset to jump to. */
data class SymbolValue(val name: String, val kind: String, val filePath: String, val offset: Int, val container: String?)

/** member hit: member name, owner type FQN, kind (method/field), and a short signature hint. */
data class MemberValue(val name: String, val owner: String, val kind: String, val signature: String)

object ClassNameExternalizer : Externalizer<ClassNameValue> {
    override fun write(out: DataOutput, value: ClassNameValue) {
        out.writeUTF(value.fqn); out.writeByte(value.origin.ordinal); out.writeUTF(value.kind)
    }
    override fun read(inp: DataInput) =
        ClassNameValue(inp.readUTF(), IndexOrigin.entries[inp.readByte().toInt()], inp.readUTF())
}

object StringExternalizer : Externalizer<String> {
    override fun write(out: DataOutput, value: String) = out.writeUTF(value)
    override fun read(inp: DataInput): String = inp.readUTF()
}

object SymbolExternalizer : Externalizer<SymbolValue> {
    override fun write(out: DataOutput, value: SymbolValue) {
        out.writeUTF(value.name); out.writeUTF(value.kind); out.writeUTF(value.filePath)
        out.writeInt(value.offset); out.writeBoolean(value.container != null)
        if (value.container != null) out.writeUTF(value.container)
    }
    override fun read(inp: DataInput): SymbolValue {
        val name = inp.readUTF(); val kind = inp.readUTF(); val file = inp.readUTF(); val off = inp.readInt()
        val container = if (inp.readBoolean()) inp.readUTF() else null
        return SymbolValue(name, kind, file, off, container)
    }
}

object MemberExternalizer : Externalizer<MemberValue> {
    override fun write(out: DataOutput, value: MemberValue) {
        out.writeUTF(value.name); out.writeUTF(value.owner); out.writeUTF(value.kind); out.writeUTF(value.signature)
    }
    override fun read(inp: DataInput) = MemberValue(inp.readUTF(), inp.readUTF(), inp.readUTF(), inp.readUTF())
}
