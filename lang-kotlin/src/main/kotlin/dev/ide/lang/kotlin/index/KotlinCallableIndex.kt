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
import dev.ide.lang.kotlin.symbols.KotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.KotlinTypeContext
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import java.io.DataInput
import java.io.DataOutput

/**
 * `kotlin.callables` — the persistent, prefix-queryable index for classpath **extensions** and **top-level
 * callables** (`println`, `listOf`, `String.trim`, `Modifier.padding`, …), the part of the Kotlin backend
 * that an in-memory scan (`ClasspathReader.scan`) used to hold whole. Mirrors [KotlinTypeShapeIndex]: per
 * `.class` it decodes the file/multi-file facade's `@kotlin.Metadata` and emits one entry per callable; the
 * engine persists it per artifact (content-hashed), block-caches it, and rebuilds incrementally. So a large
 * Kotlin classpath (Compose/AndroidX) is decoded once, survives analyzer eviction AND relaunch, and a
 * keystroke becomes a bounded prefix query instead of materializing every library's callable surface.
 *
 * **Keys are tagged + prefix-scannable** (so one index serves both query shapes against the ordered terms):
 *  - top-level callable → `"top:$name"`            → `prefix("top:$namePrefix")` / `exact("top:$name")`
 *  - extension          → `"ext:$receiverFqn $name"` → `prefix("ext:$receiver $namePrefix")` per receiver
 *
 * The space separator is safe (FQNs/identifiers never contain one) and keeps each receiver's extensions
 * contiguous, so a receiver's bucket — even the large `kotlin.Any` one — is both name- and receiver-filtered
 * on disk. Values are context-free ([CallableShape]); the consumer ([dev.ide.lang.kotlin.symbols.KotlinSymbolService])
 * rebinds the resolution context at query time, exactly as `kotlin.typeShape` does.
 */
object KotlinCallableIndex : IndexExtension<String, CallableShape> {
    override val id = IndexId("kotlin.callables")
    override val version = 2 // v2: + CallableShape.varargParamIndex (vararg-aware call resolution)
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = CallableShapeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY // queried by prefix on a tagged key; no fuzzy
    override val inputFilter = InputFilter {
        (it.origin == IndexOrigin.SDK || it.origin == IndexOrigin.LIBRARY) && it.unitName?.endsWith(".class") == true
    }

    const val TOP_PREFIX = "top:"
    const val EXT_PREFIX = "ext:"

    /** Tagged key for a top-level callable's [name]. */
    fun topKey(name: String) = "$TOP_PREFIX$name"

    /** Tagged key for an extension named [name] on receiver [receiverFqn]. */
    fun extKey(receiverFqn: String, name: String) = "$EXT_PREFIX$receiverFqn $name"

    /** Tagged key prefix scoping to receiver [receiverFqn] + a name starting with [namePrefix]. */
    fun extPrefix(receiverFqn: String, namePrefix: String) = "$EXT_PREFIX$receiverFqn $namePrefix"

    override fun index(input: IndexInput): Map<String, Collection<CallableShape>> {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        // Only a Kotlin @Metadata facade carries top-level/extension callables; plain Java/Android .class
        // (no metadata) yields nothing here (its member shape is the `kotlin.typeShape` index's job).
        val decoded = runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull() ?: return emptyMap()
        if (decoded.topLevel.isEmpty() && decoded.extensions.isEmpty()) return emptyMap()
        val unit = input.unitName?.removeSuffix(".class")
        val pkg = unit?.substringBeforeLast('/', "")?.replace('/', '.')?.ifEmpty { null }
        // The .class entry IS the JVM facade these callables compile into (a multi-file part overrides it via
        // the decode's facadeClassFqn / the symbol's declaringClassFqn) — what the interpreter reflects into.
        val facade = unit?.replace('/', '.')
        val out = HashMap<String, MutableList<CallableShape>>()
        decoded.topLevel.forEach { s ->
            out.getOrPut(topKey(s.name)) { ArrayList() }.add(CallableShape.from(s, pkg, facade))
        }
        decoded.extensions.forEach { s ->
            val recv = s.receiverTypeFqn ?: return@forEach
            out.getOrPut(extKey(recv, s.name)) { ArrayList() }.add(CallableShape.from(s, pkg, facade))
        }
        return out
    }
}

/**
 * A context-free, serializable extension / top-level callable — the indexed value. Carries everything the
 * completion item + auto-import + interpreter need (receiver, package, declaring facade, generic shape); the
 * consumer calls [toSymbol] to rebind the live [KotlinTypeContext] so `members()`/`supertypes()` work again.
 * The field set + order mirror the per-jar `.kxt` scan record so the two paths stay interchangeable.
 */
class CallableShape(
    val name: String,
    val kind: SymbolKind,
    val receiverFqn: String?,
    val signature: String?,
    val packageName: String?,
    val receiverTypeParam: String?,
    val typeParameters: List<String>,
    val returnType: KotlinType?,
    val paramTypes: List<KotlinType?>,
    val receiverTypeArgs: List<KotlinType>,
    val declaringClassFqn: String?,
    val paramNames: List<String>,
    val isComposable: Boolean,
    val isInline: Boolean,
    val varargParamIndex: Int = -1,
) {
    fun toSymbol(ctx: KotlinTypeContext?): KotlinSymbol = KotlinSymbol(
        name = name,
        kind = kind,
        type = returnType?.withContext(ctx),
        origin = BINARY,
        receiverTypeFqn = receiverFqn,
        signature = signature,
        typeParameters = typeParameters,
        paramTypes = paramTypes.map { it?.withContext(ctx) },
        paramNames = paramNames,
        receiverTypeArgs = receiverTypeArgs.map { it.withContext(ctx) },
        receiverTypeParam = receiverTypeParam,
        packageName = packageName,
        declaringClassFqn = declaringClassFqn,
        isComposable = isComposable,
        isInline = isInline,
        varargParamIndex = varargParamIndex,
    )

    companion object {
        private val BINARY = SymbolOrigin(fromSource = false, file = null)

        fun from(s: KotlinSymbol, pkg: String?, facade: String?): CallableShape = CallableShape(
            s.name, s.kind, s.receiverTypeFqn, s.signature, pkg, s.receiverTypeParam, s.typeParameters,
            s.type as? KotlinType,
            s.paramTypes.map { it as? KotlinType },
            s.receiverTypeArgs.mapNotNull { it as? KotlinType },
            // The decode already set the facade for a multi-file class part; else use the .class entry.
            s.declaringClassFqn ?: facade,
            s.paramNames,
            s.isComposable,
            s.isInline,
            s.varargParamIndex,
        )
    }
}

/** Context-free codec for a [CallableShape] (the engine reloads it; the consumer rebinds the context). */
object CallableShapeExternalizer : Externalizer<CallableShape> {
    override fun write(out: DataOutput, value: CallableShape) {
        out.writeUTF(value.name)
        out.writeByte(value.kind.ordinal)
        out.writeUTF(value.receiverFqn ?: "")
        out.writeUTF(value.signature ?: "")
        out.writeUTF(value.packageName ?: "")
        out.writeUTF(value.receiverTypeParam ?: "")
        out.writeInt(value.typeParameters.size); value.typeParameters.forEach { out.writeUTF(it) }
        writeType(out, value.returnType)
        out.writeInt(value.paramTypes.size); value.paramTypes.forEach { writeType(out, it) }
        out.writeInt(value.receiverTypeArgs.size); value.receiverTypeArgs.forEach { writeType(out, it) }
        out.writeUTF(value.declaringClassFqn ?: "")
        out.writeInt(value.paramNames.size); value.paramNames.forEach { out.writeUTF(it) }
        out.writeBoolean(value.isComposable)
        out.writeBoolean(value.isInline)
        out.writeInt(value.varargParamIndex)
    }

    override fun read(inp: DataInput): CallableShape {
        val name = inp.readUTF()
        val kind = SymbolKind.entries[inp.readByte().toInt()]
        val receiver = inp.readUTF().ifEmpty { null }
        val sig = inp.readUTF().ifEmpty { null }
        val pkg = inp.readUTF().ifEmpty { null }
        val recvParam = inp.readUTF().ifEmpty { null }
        val tps = List(inp.readInt()) { inp.readUTF() }
        val ret = readType(inp)
        val params = List(inp.readInt()) { readType(inp) }
        val recvArgs = List(inp.readInt()) { readType(inp) }.filterNotNull()
        val declaringFqn = inp.readUTF().ifEmpty { null }
        val paramNames = List(inp.readInt()) { inp.readUTF() }
        val isComposable = inp.readBoolean()
        val isInline = inp.readBoolean()
        val varargIdx = inp.readInt()
        return CallableShape(name, kind, receiver, sig, pkg, recvParam, tps, ret, params, recvArgs, declaringFqn, paramNames, isComposable, isInline, varargIdx)
    }

    /** Recursive, context-free encoding of a [KotlinType] (fqn + flags + args). */
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
        val n = inp.readInt()
        val args = ArrayList<TypeRef>(n)
        repeat(n) { readType(inp)?.let { args.add(it) } }
        return KotlinType(fqn, args, nullable, context = null, isTypeParameter = isTp, isExtensionFunctionType = isExtFn, isComposable = isComposable)
    }
}
