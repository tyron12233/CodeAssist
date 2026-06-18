package dev.ide.lang.kotlin.index

import dev.ide.index.Externalizer
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.lang.kotlin.symbols.JavaBytecode
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import java.io.DataInput
import java.io.DataOutput

/**
 * `kotlin.typeShape` — the owner-keyed member-shape index for classpath BINARIES, the persistent backing
 * for the Kotlin backend's `membersOf`. Per `.class` it decodes the type's [TypeShape] (own members with
 * full generic signatures, generic supertypes, type parameters + bounds) — `@Metadata` Kotlin classes via
 * [KotlinMetadata], plain Java/Android types via [JavaBytecode] — keyed by the dotted owner FQN
 * (`a.b.Outer.Inner`). The engine persists it per artifact (content-hashed) and block-caches it, so
 * `android.jar`'s ~40k types are decoded once, survive analyzer eviction AND relaunch, and a `.` lookup
 * becomes an index query instead of a ZipFile inflate + ASM/metadata parse.
 *
 * Type-argument binding stays at query time ([KotlinSymbolService] substitutes the receiver's args into the
 * stored generic shape), so one indexed shape serves every instantiation. Project SOURCE is NOT here — the
 * live overlay owns the edited buffer (`.kt` via the in-memory source model, `.java` via `java.membersByOwner`).
 * Kotlin built-ins (`List`/`String`/…) come from `.kotlin_builtins`, checked before this index.
 */
object KotlinTypeShapeIndex : IndexExtension<String, TypeShape> {
    override val id = IndexId("kotlin.typeShape")
    override val version = 9 // v9: class MEMBER extensions kept in the shape (scope member-ext like RowScope.weight)
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = TypeShapeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY // queried only by exact owner FQN
    override val inputFilter = InputFilter {
        (it.origin == IndexOrigin.SDK || it.origin == IndexOrigin.LIBRARY) && it.unitName?.endsWith(".class") == true
    }

    override fun index(input: IndexInput): Map<String, Collection<TypeShape>> {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        // A Kotlin @Metadata class (skip file/multifile facades — those feed the extension/top-level scan,
        // not a type's member shape); otherwise plain Java/Android bytecode.
        runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull()?.let { d ->
            val fqn = d.classFqn ?: return emptyMap()
            return mapOf(fqn to listOf(TypeShape.of(d, null)))
        }
        val js = runCatching { JavaBytecode.read(bytes, null) }.getOrNull() ?: return emptyMap()
        // The consumer queries dotted FQNs (`Outer.Inner`); the entry is `Outer$Inner.class`.
        val fqn = input.unitName?.removeSuffix(".class")?.replace('/', '.')?.replace('$', '.') ?: return emptyMap()
        return mapOf(fqn to listOf(TypeShape.of(js)))
    }
}

/** Context-free codec for a [TypeShape] (the engine reloads it; the consumer rebinds the resolution context). */
object TypeShapeExternalizer : Externalizer<TypeShape> {
    private val BINARY = SymbolOrigin(fromSource = false, file = null)

    override fun write(out: DataOutput, value: TypeShape) {
        writeStrings(out, value.typeParameters)
        writeTypes(out, value.typeParameterBounds)
        writeTypes(out, value.supertypes)
        out.writeInt(value.members.size)
        value.members.forEach { writeSymbol(out, it) }
    }

    override fun read(inp: DataInput): TypeShape {
        val tps = readStrings(inp)
        val bounds = readTypes(inp)
        val supers = readTypes(inp)
        val members = List(inp.readInt()) { readSymbol(inp) }
        return TypeShape(tps, bounds, supers, members)
    }

    private fun writeSymbol(out: DataOutput, s: KotlinSymbol) {
        out.writeUTF(s.name)
        out.writeByte(s.kind.ordinal)
        out.writeInt(modifierBits(s.modifiers))
        writeType(out, s.type as? KotlinType)
        out.writeUTF(s.receiverTypeFqn ?: "")
        out.writeUTF(s.signature ?: "")
        writeStrings(out, s.typeParameters)
        writeTypes(out, s.typeParameterBounds)
        out.writeInt(s.paramTypes.size); s.paramTypes.forEach { writeType(out, it as? KotlinType) }
        writeStrings(out, s.paramNames)
        writeTypes(out, s.receiverTypeArgs)
        out.writeUTF(s.receiverTypeParam ?: "")
        out.writeUTF(s.packageName ?: "")
        out.writeUTF(s.declaringClassFqn ?: "")
        out.writeBoolean(s.isInternal)
        out.writeBoolean(s.isComposable)
        out.writeBoolean(s.isInline)
        out.writeInt(s.varargParamIndex)
    }

    private fun readSymbol(inp: DataInput): KotlinSymbol {
        val name = inp.readUTF()
        val kind = SymbolKind.entries[inp.readByte().toInt()]
        val mods = modifiersOf(inp.readInt())
        val type = readType(inp)
        val recvFqn = inp.readUTF().ifEmpty { null }
        val sig = inp.readUTF().ifEmpty { null }
        val tps = readStrings(inp)
        val bounds = readTypes(inp)
        val params = List(inp.readInt()) { readType(inp) }
        val paramNames = readStrings(inp)
        val recvArgs = readTypes(inp)
        val recvParam = inp.readUTF().ifEmpty { null }
        val pkg = inp.readUTF().ifEmpty { null }
        val declaringFqn = inp.readUTF().ifEmpty { null }
        val internal = inp.readBoolean()
        val isComposable = inp.readBoolean()
        val isInline = inp.readBoolean()
        val varargIdx = inp.readInt()
        return KotlinSymbol(
            name = name, kind = kind, type = type, origin = BINARY, modifiers = mods,
            receiverTypeFqn = recvFqn, signature = sig, typeParameters = tps, typeParameterBounds = bounds,
            paramTypes = params, paramNames = paramNames, receiverTypeArgs = recvArgs, receiverTypeParam = recvParam,
            packageName = pkg, declaringClassFqn = declaringFqn, isInternal = internal,
            isComposable = isComposable, isInline = isInline, varargParamIndex = varargIdx,
        )
    }

    private fun modifierBits(mods: Set<Modifier>): Int = mods.fold(0) { acc, m -> acc or (1 shl m.ordinal) }
    private fun modifiersOf(bits: Int): Set<Modifier> =
        Modifier.entries.filterTo(HashSet()) { bits and (1 shl it.ordinal) != 0 }

    private fun writeStrings(out: DataOutput, xs: List<String>) { out.writeInt(xs.size); xs.forEach { out.writeUTF(it) } }
    private fun readStrings(inp: DataInput): List<String> = List(inp.readInt()) { inp.readUTF() }

    private fun writeTypes(out: DataOutput, ts: List<TypeRef>) { out.writeInt(ts.size); ts.forEach { writeType(out, it as? KotlinType) } }
    private fun readTypes(inp: DataInput): List<TypeRef> = buildList { repeat(inp.readInt()) { readType(inp)?.let(::add) } }

    /** Recursive, context-free encoding of a [KotlinType] (fqn + flags + args); mirrors the `.kxt` codec. */
    private fun writeType(out: DataOutput, t: KotlinType?) {
        out.writeBoolean(t != null)
        if (t == null) return
        out.writeUTF(t.qualifiedName)
        out.writeBoolean(t.nullable)
        out.writeBoolean(t.isTypeParameter)
        out.writeBoolean(t.isExtensionFunctionType)
        out.writeBoolean(t.isComposable)
        out.writeInt(t.typeArguments.size)
        t.typeArguments.forEach { writeType(out, it as? KotlinType) }
    }

    private fun readType(inp: DataInput): KotlinType? {
        if (!inp.readBoolean()) return null
        val fqn = inp.readUTF()
        val nullable = inp.readBoolean()
        val isTp = inp.readBoolean()
        val isExtFn = inp.readBoolean()
        val isComposable = inp.readBoolean()
        val args = buildList<TypeRef> { repeat(inp.readInt()) { readType(inp)?.let(::add) } }
        return KotlinType(fqn, args, nullable, context = null, isTypeParameter = isTp, isExtensionFunctionType = isExtFn, isComposable = isComposable)
    }
}
