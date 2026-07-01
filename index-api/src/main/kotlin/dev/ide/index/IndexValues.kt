package dev.ide.index

import java.io.DataInput
import java.io.DataOutput

/**
 * Value payloads for the v1 indexes, shared by their producers (index-impl, lang-jdt) and consumers
 * (resolution/completion). Each has a matching [Externalizer] for the on-disk per-artifact cache.
 */

/** classNames hit: the FQN, where it came from, and its kind (drives the completion badge + auto-import). */
data class ClassNameValue(val fqn: String, val origin: IndexOrigin, val kind: String)

/**
 * go-to-symbol hit: declaration name, kind, the source file, and the offset to jump to. The file is stored
 * as the interned [fileId] (resolve with `IndexService.filePath`), NOT a path string — a file declaring many
 * symbols would otherwise repeat its full path once per symbol, both in RAM and on disk.
 */
data class SymbolValue(val name: String, val kind: String, val fileId: Int, val offset: Int, val container: String?)

/** member hit: member name, owner type FQN, kind (method/field), and a short signature hint. */
data class MemberValue(val name: String, val owner: String, val kind: String, val signature: String)

/**
 * source-doc hit: real parameter [names] + cleaned [doc] (javadoc/KDoc) for a method declared on the owner
 * type the index is keyed by, recovered from attached SOURCES (a compiled artifact carries neither). The
 * type's own doc is stored as the entry with an empty [name] (and [arity] -1). [arity] picks the overload.
 */
data class SourceDocValue(val name: String, val arity: Int, val names: List<String>, val doc: String?)

/**
 * runnable-entry-point hit: a class the Run action can launch. [fqn] is what the runner loads — a Kotlin file
 * facade (`com.example.MainKt`), a `@JvmStatic` owner, a Java class, or an instance-main class. [fileId] is the
 * interned source file (resolve via [IndexService.filePath]) so a hit can be scoped to a module's roots.
 * [instance] is true when there is no static `main`, so the runner constructs the class (no-arg constructor)
 * and calls the instance `main` — the JVM launcher can't (it requires a static entry point), but our reflective
 * runner can (matching Java's newer instance-`main` entry points).
 */
data class EntryPointValue(val fqn: String, val fileId: Int, val instance: Boolean)

/**
 * Shared identifiers for the runnable-entry-point indexes (one per source language). Producers ([the Java and
 * Kotlin backends]) and the consumer (the run service) agree on the [IndexId]s and the single [KEY] every
 * entry point is stored under, so a run lookup is `exact(id, KEY)` merged across both.
 */
object EntryPointIndex {
    val JAVA = IndexId("java.mains")
    val KOTLIN = IndexId("kotlin.mains")

    /** The single term every entry point is keyed by (there is nothing to prefix/fuzzy match — we want them all). */
    const val KEY = "main"
}

object EntryPointExternalizer : Externalizer<EntryPointValue> {
    override fun write(out: DataOutput, value: EntryPointValue) {
        out.writeUTF(value.fqn); out.writeInt(value.fileId); out.writeBoolean(value.instance)
    }
    override fun read(inp: DataInput) = EntryPointValue(inp.readUTF(), inp.readInt(), inp.readBoolean())
}

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
        out.writeUTF(value.name); out.writeUTF(value.kind); out.writeInt(value.fileId)
        out.writeInt(value.offset); out.writeBoolean(value.container != null)
        if (value.container != null) out.writeUTF(value.container)
    }
    override fun read(inp: DataInput): SymbolValue {
        val name = inp.readUTF(); val kind = inp.readUTF(); val fileId = inp.readInt(); val off = inp.readInt()
        val container = if (inp.readBoolean()) inp.readUTF() else null
        return SymbolValue(name, kind, fileId, off, container)
    }
}

object MemberExternalizer : Externalizer<MemberValue> {
    override fun write(out: DataOutput, value: MemberValue) {
        out.writeUTF(value.name); out.writeUTF(value.owner); out.writeUTF(value.kind); out.writeUTF(value.signature)
    }
    override fun read(inp: DataInput) = MemberValue(inp.readUTF(), inp.readUTF(), inp.readUTF(), inp.readUTF())
}

object SourceDocExternalizer : Externalizer<SourceDocValue> {
    override fun write(out: DataOutput, value: SourceDocValue) {
        out.writeUTF(value.name)
        out.writeInt(value.arity)
        out.writeInt(value.names.size); value.names.forEach { out.writeUTF(it) }
        out.writeBoolean(value.doc != null); if (value.doc != null) out.writeUTF(value.doc)
    }
    override fun read(inp: DataInput): SourceDocValue {
        val name = inp.readUTF(); val arity = inp.readInt()
        val names = List(inp.readInt()) { inp.readUTF() }
        val doc = if (inp.readBoolean()) inp.readUTF() else null
        return SourceDocValue(name, arity, names, doc)
    }
}
