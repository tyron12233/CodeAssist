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
 * via the compiler's own (unrelocated) `org.jetbrains.kotlin.metadata.*` classes from kotlin-compiler-embeddable.
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
                modifiers = if (static) setOf(Modifier.STATIC) else emptySet(),
                origin = BINARY,
                signature = "($params): ${typeText(ret, nr, tp, tt)}",
                typeParameters = f.typeParameterList.map { nr.getString(it.name) },
                paramTypes = f.valueParameterList.map { typeRef(it.varargElementType(tt) ?: it.type(tt), nr, tp, tt) },
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
                modifiers = if (static) setOf(Modifier.STATIC) else emptySet(),
                origin = BINARY,
                signature = ": ${typeText(ret, nr, tp, tt)}",
            )
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
