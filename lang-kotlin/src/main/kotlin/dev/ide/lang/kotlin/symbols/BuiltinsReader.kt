package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.builtins.BuiltInsBinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.receiverType
import org.jetbrains.kotlin.metadata.deserialization.returnType
import org.jetbrains.kotlin.metadata.deserialization.supertypes
import org.jetbrains.kotlin.metadata.deserialization.type
import org.jetbrains.kotlin.metadata.deserialization.varargElementType
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Decodes Kotlin's built-in declarations (`List`, `Map`, `Int`, `String`, `Collection`, etc.) from the
 * `.kotlin_builtins` binaries shipped in `kotlin-stdlib.jar` (`kotlin/collections/collections.kotlin_builtins`,
 * `kotlin/kotlin.kotlin_builtins`, etc.). These are the actual Kotlin types, so they need not be approximated
 * via `java.util.List`/`java.lang.Integer` (which over-included mutating + static methods). A read-only
 * `List` has exactly its Kotlin members (no `add`/`remove`), and `Int.` shows its companion's `MAX_VALUE`.
 *
 * The `.kotlin_builtins` format is the common metadata protobuf with a [BuiltInsBinaryVersion] header, read
 * via the compiler's own (unrelocated) `org.jetbrains.kotlin.metadata.*` classes from the bundled compiler.
 * Companion-object members are tagged [Modifier.STATIC] so the instance/type member filter shows them only on
 * type access (`Int.`).
 *
 * The decode produces a context-free [TypeShape] per type, so the SAME shape is what the `kotlin.builtins`
 * index ([dev.ide.lang.kotlin.index.KotlinBuiltinsIndex]) persists — [shapesFrom] is the shared producer. This
 * live reader is used only when NO index is wired (standalone / tests); the IDE queries the index instead.
 */
class BuiltinsReader(private val jars: List<Path>) {

    private val byFqn: Map<String, TypeShape> by lazy { load() }

    fun isBuiltin(fqn: String): Boolean = fqn in byFqn

    /** The built-in shape for [fqn] (members + companion-as-static + supertypes + type params), rebound to
     *  [ctx] so its contained types resolve again; null if [fqn] is not a Kotlin built-in. */
    fun lookup(fqn: String, ctx: KotlinTypeContext?): TypeShape? = byFqn[fqn]?.withContext(ctx)

    /** Every TOP-LEVEL/extension callable declared in the jars' `.kotlin_builtins` fragments — the compiler
     *  intrinsics (`arrayOf`, `intArrayOf`, `emptyArray`, …) that have NO `.class` file. Used only when NO
     *  index is wired (standalone / tests); the IDE reads them from the `kotlin.builtinCallables` index. */
    fun topLevelCallables(): List<KotlinSymbol> = callables

    private val callables: List<KotlinSymbol> by lazy { loadCallables() }

    private fun loadCallables(): List<KotlinSymbol> {
        val out = ArrayList<KotlinSymbol>()
        for (jar in jars) {
            val z = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: continue
            z.use {
                val entries = it.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".kotlin_builtins")) continue
                    val pkg = e.name.substringBeforeLast('/', "").replace('/', '.')
                    runCatching {
                        val bytes = it.getInputStream(e).use { s -> s.readBytes() }
                        out += callablesFrom(bytes, pkg)
                    }
                }
            }
        }
        return out
    }

    private fun load(): Map<String, TypeShape> {
        val out = HashMap<String, TypeShape>()
        for (jar in jars) {
            val z = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: continue
            z.use {
                val entries = it.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".kotlin_builtins")) continue
                    runCatching {
                        val bytes = it.getInputStream(e).use { s -> s.readBytes() }
                        out.putAll(shapesFrom(bytes))
                    }
                }
            }
        }
        return out
    }

    companion object {
        private val BINARY = SymbolOrigin(fromSource = false, file = null)

        /** Decode one `.kotlin_builtins` package fragment into context-free [TypeShape]s, keyed by Kotlin FQN
         *  (`kotlin.collections.List`). Companion-object members are merged into the owner as statics, exactly
         *  as a `Type.` reference sees them. The shared producer for the live reader AND the persistent index. */
        fun shapesFrom(bytes: ByteArray): Map<String, TypeShape> = runCatching {
            val stream = ByteArrayInputStream(bytes)
            BuiltInsBinaryVersion.readFrom(stream) // consume the version header
            val frag = ProtoBuf.PackageFragment.parseFrom(stream, BuiltInSerializerProtocol.extensionRegistry)
            val nr = NameResolverImpl(frag.strings, frag.qualifiedNames)
            val classes = frag.class_List.associateBy { nr.getQualifiedClassName(it.fqName).replace('/', '.') }
            val out = HashMap<String, TypeShape>(classes.size)
            for ((fqn, cls) in classes) {
                val owner = KotlinSymbol(fqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
                val classTp = cls.typeParameterList.associate { it.id to nr.getString(it.name) }
                // Modern Kotlin metadata stores member/supertype types in a per-class TYPE TABLE, referenced by
                // id; only the legacy format inlines them. The extension accessors (`returnType(tt)`, `type(tt)`,
                // `supertypes(tt)`, …) read the table when an id is present and fall back to the inline field
                // otherwise — so without this a builtin member's type decoded as null (e.g. `Map.Entry.value: V`).
                val tt = TypeTable(cls.typeTable)
                val members = ArrayList<KotlinSymbol>()
                cls.functionList.filterNot { it.hasReceiverType() }.forEach { members += func(it, nr, classTp, owner, static = false, tt = tt) }
                cls.propertyList.filterNot { it.hasReceiverType() }.forEach { members += prop(it, nr, classTp, owner, static = false, tt = tt) }
                // Companion object: its members behave like statics (`Int.MAX_VALUE`).
                val companionName = if (cls.hasCompanionObjectName()) nr.getString(cls.companionObjectName) else null
                companionName?.let { name ->
                    classes["$fqn.$name"]?.let { comp ->
                        val compTp = comp.typeParameterList.associate { it.id to nr.getString(it.name) }
                        val compTt = TypeTable(comp.typeTable)
                        comp.functionList.filterNot { it.hasReceiverType() }.forEach { members += func(it, nr, compTp, owner, static = true, tt = compTt) }
                        comp.propertyList.filterNot { it.hasReceiverType() }.forEach { members += prop(it, nr, compTp, owner, static = true, tt = compTt) }
                    }
                }
                val supers = cls.supertypes(tt).mapNotNull { typeRef(it, nr, classTp, tt) }
                val isObject = Flags.CLASS_KIND.get(cls.flags) == ProtoBuf.Class.Kind.OBJECT
                out[fqn] = TypeShape(
                    typeParameters = cls.typeParameterList.map { nr.getString(it.name) },
                    typeParameterBounds = emptyList(),
                    supertypes = supers,
                    members = members,
                    isObject = isObject,
                    companionObjectName = companionName,
                    isKotlin = true,
                    isInterface = Flags.CLASS_KIND.get(cls.flags) == ProtoBuf.Class.Kind.INTERFACE,
                    isAbstract = Flags.MODALITY.get(cls.flags).let { it == ProtoBuf.Modality.ABSTRACT || it == ProtoBuf.Modality.SEALED },
                )
            }
            out
        }.getOrDefault(emptyMap())

        private fun func(f: ProtoBuf.Function, nr: NameResolverImpl, classTp: Map<Int, String>, owner: KotlinSymbol, static: Boolean, tt: TypeTable): KotlinSymbol {
            val tp = classTp + f.typeParameterList.associate { it.id to nr.getString(it.name) }
            val params = f.valueParameterList.joinToString(", ") { vp ->
                "${nr.getString(vp.name)}: ${typeText(vp.varargElementType(tt) ?: vp.type(tt), nr, tp, tt)}"
            }
            val ret = f.returnType(tt)
            return KotlinSymbol(
                name = nr.getString(f.name),
                kind = SymbolKind.METHOD,
                type = typeRef(ret, nr, tp, tt),
                owner = owner,
                modifiers = (if (static) setOf(Modifier.STATIC) else emptySet()) + abstractFlag(f.flags),
                origin = BINARY,
                signature = "($params): ${typeText(ret, nr, tp, tt)}",
                typeParameters = f.typeParameterList.map { nr.getString(it.name) },
                paramTypes = f.valueParameterList.map { typeRef(it.varargElementType(tt) ?: it.type(tt), nr, tp, tt) },
                isInfix = Flags.IS_INFIX.get(f.flags),
                varargParamIndex = f.valueParameterList.indexOfFirst { it.varargElementType(tt) != null },
            )
        }

        private fun prop(p: ProtoBuf.Property, nr: NameResolverImpl, classTp: Map<Int, String>, owner: KotlinSymbol, static: Boolean, tt: TypeTable): KotlinSymbol {
            val tp = classTp + p.typeParameterList.associate { it.id to nr.getString(it.name) }
            val ret = p.returnType(tt)
            return KotlinSymbol(
                name = nr.getString(p.name),
                kind = SymbolKind.FIELD,
                type = typeRef(ret, nr, tp, tt),
                owner = owner,
                modifiers = (if (static) setOf(Modifier.STATIC) else emptySet()) + abstractFlag(p.flags),
                origin = BINARY,
                signature = ": ${typeText(ret, nr, tp, tt)}",
            )
        }

        /** ABSTRACT modality on a builtin member (e.g. `Iterator.next`, `Comparable.compareTo`) → [Modifier.ABSTRACT],
         *  so implementing a builtin interface requires an override. A member with a default body decodes OPEN. */
        private fun abstractFlag(flags: Int): Set<Modifier> =
            if (Flags.MODALITY.get(flags) == ProtoBuf.Modality.ABSTRACT) setOf(Modifier.ABSTRACT) else emptySet()

        /** Decode the TOP-LEVEL (package-level) callables of one `.kotlin_builtins` fragment — the compiler
         *  INTRINSICS with NO `.class` file (`arrayOf`, `intArrayOf`, `charArrayOf`, `emptyArray`, …), so neither
         *  [shapesFrom] (types only) nor the `.class`-scanning `KotlinCallableIndex` ever surfaces them.
         *  [packageName] is the fragment's Kotlin package (`kotlin`, `kotlin.collections`, …), which the caller
         *  derives from the entry path. A declaration with a receiver is emitted as an extension (receiver set);
         *  the rest as top-level. Context-free like [shapesFrom] — the consumer rebinds the context. */
        fun callablesFrom(bytes: ByteArray, packageName: String): List<KotlinSymbol> = runCatching {
            val stream = ByteArrayInputStream(bytes)
            BuiltInsBinaryVersion.readFrom(stream) // consume the version header
            val frag = ProtoBuf.PackageFragment.parseFrom(stream, BuiltInSerializerProtocol.extensionRegistry)
            val nr = NameResolverImpl(frag.strings, frag.qualifiedNames)
            val pkg = frag.getPackage()
            val tt = TypeTable(pkg.typeTable)
            val out = ArrayList<KotlinSymbol>(pkg.functionCount + pkg.propertyCount)
            pkg.functionList.forEach { out += pkgFunc(it, nr, packageName, tt) }
            pkg.propertyList.forEach { out += pkgProp(it, nr, packageName, tt) }
            out
        }.getOrDefault(emptyList())

        private fun pkgFunc(f: ProtoBuf.Function, nr: NameResolverImpl, pkg: String, tt: TypeTable): KotlinSymbol {
            val tp = f.typeParameterList.associate { it.id to nr.getString(it.name) }
            val ret = f.returnType(tt)
            val receiver = f.receiverType(tt)
            val params = f.valueParameterList.joinToString(", ") { vp ->
                "${nr.getString(vp.name)}: ${typeText(vp.varargElementType(tt) ?: vp.type(tt), nr, tp, tt)}"
            }
            return KotlinSymbol(
                name = nr.getString(f.name),
                kind = SymbolKind.METHOD,
                type = typeRef(ret, nr, tp, tt),
                modifiers = visibilityMods(f.flags),
                origin = BINARY,
                receiverTypeFqn = receiver?.let { receiverFqnOf(it, nr) },
                signature = "($params): ${typeText(ret, nr, tp, tt)}",
                typeParameters = f.typeParameterList.map { nr.getString(it.name) },
                paramTypes = f.valueParameterList.map { typeRef(it.varargElementType(tt) ?: it.type(tt), nr, tp, tt) },
                paramNames = f.valueParameterList.map { nr.getString(it.name) },
                receiverTypeArgs = receiverArgs(receiver, nr, tp, tt),
                receiverTypeParam = receiver?.let { receiverTypeParamOf(it, nr, tp) },
                packageName = pkg,
                isInternal = Flags.VISIBILITY.get(f.flags) == ProtoBuf.Visibility.INTERNAL,
                isInline = Flags.IS_INLINE.get(f.flags),
                isInfix = Flags.IS_INFIX.get(f.flags),
                isSuspend = Flags.IS_SUSPEND.get(f.flags),
                varargParamIndex = f.valueParameterList.indexOfFirst { it.varargElementType(tt) != null },
                paramHasDefault = f.valueParameterList.map { Flags.DECLARES_DEFAULT_VALUE.get(it.flags) },
            )
        }

        private fun pkgProp(p: ProtoBuf.Property, nr: NameResolverImpl, pkg: String, tt: TypeTable): KotlinSymbol {
            val tp = p.typeParameterList.associate { it.id to nr.getString(it.name) }
            val ret = p.returnType(tt)
            val receiver = p.receiverType(tt)
            return KotlinSymbol(
                name = nr.getString(p.name),
                kind = SymbolKind.FIELD,
                type = typeRef(ret, nr, tp, tt),
                modifiers = visibilityMods(p.flags),
                origin = BINARY,
                receiverTypeFqn = receiver?.let { receiverFqnOf(it, nr) },
                signature = ": ${typeText(ret, nr, tp, tt)}",
                typeParameters = p.typeParameterList.map { nr.getString(it.name) },
                receiverTypeArgs = receiverArgs(receiver, nr, tp, tt),
                receiverTypeParam = receiver?.let { receiverTypeParamOf(it, nr, tp) },
                packageName = pkg,
                isInternal = Flags.VISIBILITY.get(p.flags) == ProtoBuf.Visibility.INTERNAL,
            )
        }

        private fun receiverArgs(receiver: ProtoBuf.Type?, nr: NameResolverImpl, tp: Map<Int, String>, tt: TypeTable): List<TypeRef> =
            receiver?.argumentList?.mapNotNull { a ->
                if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) null else a.type(tt)?.let { typeRef(it, nr, tp, tt) }
            } ?: emptyList()

        /** The receiver's class FQN for an extension; a bare type-parameter receiver keys on `kotlin.Any`. */
        private fun receiverFqnOf(t: ProtoBuf.Type, nr: NameResolverImpl): String? = when {
            t.hasClassName() -> nr.getQualifiedClassName(t.className).replace('/', '.')
            t.hasTypeParameter() || t.hasTypeParameterName() -> "kotlin.Any"
            else -> null
        }

        private fun receiverTypeParamOf(t: ProtoBuf.Type, nr: NameResolverImpl, tp: Map<Int, String>): String? = when {
            t.hasTypeParameter() -> tp[t.typeParameter]
            t.hasTypeParameterName() -> nr.getString(t.typeParameterName)
            else -> null
        }

        private fun visibilityMods(flags: Int): Set<Modifier> = when (Flags.VISIBILITY.get(flags)) {
            ProtoBuf.Visibility.PRIVATE, ProtoBuf.Visibility.PRIVATE_TO_THIS, ProtoBuf.Visibility.LOCAL -> setOf(Modifier.PRIVATE)
            ProtoBuf.Visibility.PROTECTED -> setOf(Modifier.PROTECTED)
            else -> emptySet()
        }

        private fun typeRef(t: ProtoBuf.Type, nr: NameResolverImpl, tp: Map<Int, String>, tt: TypeTable): TypeRef? = when {
            t.hasTypeParameter() -> KotlinType(tp[t.typeParameter] ?: "T", nullable = t.nullable, context = null, isTypeParameter = true)
            t.hasTypeParameterName() -> KotlinType(nr.getString(t.typeParameterName), nullable = t.nullable, context = null, isTypeParameter = true)
            t.hasClassName() -> {
                val fqn = nr.getQualifiedClassName(t.className).replace('/', '.')
                // A type ARGUMENT's type is itself table-referenced (`a.typeId`) in the modern format, so it must
                // be read via the table — else `Iterator<T>` decodes as a raw `Iterator` and generic member
                // inference (`list.iterator().next()`) loses the element type.
                val args = t.argumentList.mapNotNull { a ->
                    if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) null
                    else a.type(tt)?.let { typeRef(it, nr, tp, tt) }
                }
                KotlinType(fqn, args, nullable = t.nullable, context = null)
            }
            else -> null
        }

        private fun typeText(t: ProtoBuf.Type, nr: NameResolverImpl, tp: Map<Int, String>, tt: TypeTable): String = when {
            t.hasTypeParameter() -> (tp[t.typeParameter] ?: "T") + if (t.nullable) "?" else ""
            t.hasTypeParameterName() -> nr.getString(t.typeParameterName) + if (t.nullable) "?" else ""
            t.hasClassName() -> {
                val fqn = nr.getQualifiedClassName(t.className).replace('/', '.')
                val args = t.argumentList.map { a ->
                    if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) "*"
                    else a.type(tt)?.let { typeText(it, nr, tp, tt) } ?: "*"
                }
                TypeRendering.render(fqn, args, t.nullable)
            }
            else -> "?"
        }
    }
}
