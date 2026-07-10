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
import dev.ide.lang.resolve.Modifier
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
/**
 * A library/SDK callable that user code can never reach: `private` (file-scoped to its own library source) or
 * `internal` (the index only covers SDK/LIBRARY origins, always a DIFFERENT module than the user's source, so
 * their `internal` is out of scope). Such callables — e.g. the stdlib's `private fun String.getRootLength()` —
 * must not be indexed, or they leak into completion and resolve without the "cannot access, it is private"
 * the compiler reports. Public API is unaffected.
 */
private fun KotlinSymbol.inaccessibleFromAnotherModule(): Boolean =
    Modifier.PRIVATE in modifiers || isInternal

object KotlinCallableIndex : IndexExtension<String, CallableShape> {
    override val id = IndexId("kotlin.callables")
    // Base 9 (v9: + CallableShape.isInfix) + the shared-codec FORMAT (see CallableShapeExternalizer.FORMAT), so a
    // codec format change invalidates this index, kotlin.builtinCallables and kotlin.callables.source together.
    override val version = 9 + CallableShapeExternalizer.FORMAT
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = CallableShapeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY // queried by prefix on a tagged key; no fuzzy
    override val inputFilter = InputFilter {
        (it.origin == IndexOrigin.SDK || it.origin == IndexOrigin.LIBRARY) && it.unitName?.endsWith(
            ".class"
        ) == true
    }

    const val TOP_PREFIX = "top:"
    const val EXT_PREFIX = "ext:"
    const val NAME_PREFIX = "name:"

    /** Tagged key for a top-level callable's [name]. */
    fun topKey(name: String) = "$TOP_PREFIX$name"

    /** Tagged key for an extension named [name] on receiver [receiverFqn]. */
    fun extKey(receiverFqn: String, name: String) = "$EXT_PREFIX$receiverFqn $name"

    /** Tagged key prefix scoping to receiver [receiverFqn] + a name starting with [namePrefix]. */
    fun extPrefix(receiverFqn: String, namePrefix: String) = "$EXT_PREFIX$receiverFqn $namePrefix"

    /** Tagged RECEIVER-BLIND key for an extension's [name] — "any extension named X", the analog of
     *  `KotlinFunctionShortNameIndex` for the receiver-keyed side. `ext:` answers "extensions ON this
     *  receiver"; this answers "where could the unresolved `x.shout()` come from" (import suggestions).
     *  Top-level callables are already name-addressable via [topKey], so the short-name union is
     *  `exact(top:X) ∪ exact(name:X)`. Class MEMBERS stay owner-keyed in `kotlin.typeShape` (the bytecode
     *  view is in `java.members`); a member-by-short-name key can join here if the Phase 3 index provider
     *  needs it. */
    fun nameKey(name: String) = "$NAME_PREFIX$name"

    override fun index(input: IndexInput): Map<String, Collection<CallableShape>> {
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        // Only a Kotlin @Metadata facade carries top-level/extension callables; plain Java/Android .class
        // (no metadata) yields nothing here (its member shape is the `kotlin.typeShape` index's job).
        val decoded =
            runCatching { KotlinMetadata.decode(bytes, null) }.getOrNull() ?: return emptyMap()
        if (decoded.topLevel.isEmpty() && decoded.extensions.isEmpty()) return emptyMap()
        val unit = input.unitName?.removeSuffix(".class")
        val pkg = unit?.substringBeforeLast('/', "")?.replace('/', '.')?.ifEmpty { null }
        // The .class entry IS the JVM facade these callables compile into (a multi-file part overrides it via
        // the decoded's facadeClassFqn / the symbol's declaringClassFqn) — what the interpreter reflects into.
        val facade = unit?.replace('/', '.')
        val out = HashMap<String, MutableList<CallableShape>>()
        decoded.topLevel.forEach { s ->
            if (s.inaccessibleFromAnotherModule()) return@forEach
            out.getOrPut(topKey(s.name)) { ArrayList() }.add(CallableShape.from(s, pkg, facade))
        }
        decoded.extensions.forEach { s ->
            if (s.inaccessibleFromAnotherModule()) return@forEach
            val recv = s.receiverTypeFqn ?: return@forEach
            val shape = CallableShape.from(s, pkg, facade)
            out.getOrPut(extKey(recv, s.name)) { ArrayList() }.add(shape)
            out.getOrPut(nameKey(s.name)) { ArrayList() }.add(shape)
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
    val isInfix: Boolean,
    val isSuspend: Boolean,
    val varargParamIndex: Int = -1,
    val paramHasDefault: List<Boolean> = emptyList(),
    val isDeprecated: Boolean = false,
    /** Sibling type-param upper bounds (`fun <R, T : R>` → `[null, "R"]`), for `T : R` constraint inference. */
    val typeParamBoundNames: List<String?> = emptyList(),
) {
    fun toSymbol(ctx: KotlinTypeContext?, origin: SymbolOrigin = BINARY): KotlinSymbol = KotlinSymbol(
        name = name,
        kind = kind,
        type = returnType?.withContext(ctx),
        origin = origin,
        receiverTypeFqn = receiverFqn,
        signature = signature,
        typeParameters = typeParameters,
        typeParamBoundNames = typeParamBoundNames,
        paramTypes = paramTypes.map { it?.withContext(ctx) },
        paramNames = paramNames,
        receiverTypeArgs = receiverTypeArgs.map { it.withContext(ctx) },
        receiverTypeParam = receiverTypeParam,
        packageName = packageName,
        declaringClassFqn = declaringClassFqn,
        isComposable = isComposable,
        isInline = isInline,
        isInfix = isInfix,
        isSuspend = isSuspend,
        isDeprecated = isDeprecated,
        varargParamIndex = varargParamIndex,
        paramHasDefault = paramHasDefault,
    )

    companion object {
        private val BINARY = SymbolOrigin(fromSource = false, file = null)

        fun from(s: KotlinSymbol, pkg: String?, facade: String?): CallableShape = CallableShape(
            s.name,
            s.kind,
            s.receiverTypeFqn,
            s.signature,
            pkg,
            s.receiverTypeParam,
            s.typeParameters,
            s.type as? KotlinType,
            s.paramTypes.map { it as? KotlinType },
            s.receiverTypeArgs.filterIsInstance<KotlinType>(),
            // The decode already set the facade for a multi-file class part; else use the .class entry.
            s.declaringClassFqn ?: facade,
            s.paramNames,
            s.isComposable,
            s.isInline,
            s.isInfix,
            s.isSuspend,
            s.varargParamIndex,
            s.paramHasDefault,
            s.isDeprecated,
            s.typeParamBoundNames,
        )
    }
}

/** Context-free codec for a [CallableShape] (the engine reloads it; the consumer rebinds the context). */
object CallableShapeExternalizer : Externalizer<CallableShape> {
    /**
     * Wire-format version of THIS codec — same contract as [TypeShapeExternalizer.FORMAT]. Every index that
     * persists with it ([KotlinCallableIndex], [KotlinBuiltinCallableIndex], [KotlinSourceCallableIndex]) folds
     * it into its `version`, so bumping FORMAT on a [write]/[read] change invalidates all of them together and
     * no sharing index can be left reading a stale segment with the new format. Starts at 0 (each index's base
     * already encodes today's format; increment FORMAT for future changes, and only ever increase it).
     */
    const val FORMAT = 0

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
        out.writeInt(value.receiverTypeArgs.size); value.receiverTypeArgs.forEach {
            writeType(
                out, it
            )
        }
        out.writeUTF(value.declaringClassFqn ?: "")
        out.writeInt(value.paramNames.size); value.paramNames.forEach { out.writeUTF(it) }
        out.writeBoolean(value.isComposable)
        out.writeBoolean(value.isInline)
        out.writeBoolean(value.isInfix)
        out.writeBoolean(value.isSuspend)
        out.writeInt(value.varargParamIndex)
        out.writeInt(value.paramHasDefault.size); value.paramHasDefault.forEach {
            out.writeBoolean(
                it
            )
        }
        out.writeBoolean(value.isDeprecated)
        out.writeInt(value.typeParamBoundNames.size); value.typeParamBoundNames.forEach { out.writeUTF(it ?: "") }
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
        val isInfix = inp.readBoolean()
        val isSuspend = inp.readBoolean()
        val varargIdx = inp.readInt()
        val paramHasDefault = List(inp.readInt()) { inp.readBoolean() }
        val isDeprecated = inp.readBoolean()
        val boundNames = List(inp.readInt()) { inp.readUTF().ifEmpty { null } }
        return CallableShape(
            name,
            kind,
            receiver,
            sig,
            pkg,
            recvParam,
            tps,
            ret,
            params,
            recvArgs,
            declaringFqn,
            paramNames,
            isComposable,
            isInline,
            isInfix,
            isSuspend,
            varargIdx,
            paramHasDefault,
            isDeprecated,
            boundNames,
        )
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
        return KotlinType(
            fqn,
            args,
            nullable,
            context = null,
            isTypeParameter = isTp,
            isExtensionFunctionType = isExtFn,
            isComposable = isComposable
        )
    }
}
