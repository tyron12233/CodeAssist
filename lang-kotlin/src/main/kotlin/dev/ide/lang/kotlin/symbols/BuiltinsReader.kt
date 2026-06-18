package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.builtins.BuiltInsBinaryVersion
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
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
 */
class BuiltinsReader(private val jars: List<Path>) {

    private val byFqn: Map<String, Pair<ProtoBuf.Class, NameResolverImpl>> by lazy { load() }

    fun isBuiltin(fqn: String): Boolean = fqn in byFqn

    /** Decode [fqn]'s built-in shape (members + companion-as-static + supertypes + type params), or null. */
    fun lookup(fqn: String, ctx: KotlinTypeContext?): KotlinMetadata.Decoded? {
        val (cls, nr) = byFqn[fqn] ?: return null
        val owner = KotlinSymbol(fqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
        val classTp = cls.typeParameterList.associate { it.id to nr.getString(it.name) }
        val members = ArrayList<KotlinSymbol>()
        cls.functionList.filterNot { it.hasReceiverType() }.forEach { members += func(it, nr, classTp, ctx, owner, static = false) }
        cls.propertyList.filterNot { it.hasReceiverType() }.forEach { members += prop(it, nr, classTp, ctx, owner, static = false) }
        // Companion object: its members behave like statics (`Int.MAX_VALUE`).
        if (cls.hasCompanionObjectName()) {
            val companionFqn = "$fqn." + nr.getString(cls.companionObjectName)
            byFqn[companionFqn]?.let { (comp, compNr) ->
                val compTp = comp.typeParameterList.associate { it.id to compNr.getString(it.name) }
                comp.functionList.filterNot { it.hasReceiverType() }.forEach { members += func(it, compNr, compTp, ctx, owner, static = true) }
                comp.propertyList.filterNot { it.hasReceiverType() }.forEach { members += prop(it, compNr, compTp, ctx, owner, static = true) }
            }
        }
        val supers = cls.supertypeList.mapNotNull { typeRef(it, nr, classTp, ctx) }
        return KotlinMetadata.Decoded(fqn, supers, cls.typeParameterList.map { nr.getString(it.name) }, members, emptyList(), emptyList())
    }

    private fun func(f: ProtoBuf.Function, nr: NameResolverImpl, classTp: Map<Int, String>, ctx: KotlinTypeContext?, owner: KotlinSymbol, static: Boolean): KotlinSymbol {
        val tp = classTp + f.typeParameterList.associate { it.id to nr.getString(it.name) }
        val params = f.valueParameterList.joinToString(", ") { vp ->
            "${nr.getString(vp.name)}: ${typeText(if (vp.hasVarargElementType()) vp.varargElementType else vp.type, nr, tp)}"
        }
        return KotlinSymbol(
            name = nr.getString(f.name),
            kind = SymbolKind.METHOD,
            type = typeRef(f.returnType, nr, tp, ctx),
            owner = owner,
            modifiers = if (static) setOf(Modifier.STATIC) else emptySet(),
            origin = BINARY,
            signature = "($params): ${typeText(f.returnType, nr, tp)}",
            typeParameters = f.typeParameterList.map { nr.getString(it.name) },
            paramTypes = f.valueParameterList.map { typeRef(if (it.hasVarargElementType()) it.varargElementType else it.type, nr, tp, ctx) },
            varargParamIndex = f.valueParameterList.indexOfFirst { it.hasVarargElementType() },
        )
    }

    private fun prop(p: ProtoBuf.Property, nr: NameResolverImpl, classTp: Map<Int, String>, ctx: KotlinTypeContext?, owner: KotlinSymbol, static: Boolean): KotlinSymbol {
        val tp = classTp + p.typeParameterList.associate { it.id to nr.getString(it.name) }
        return KotlinSymbol(
            name = nr.getString(p.name),
            kind = SymbolKind.FIELD,
            type = typeRef(p.returnType, nr, tp, ctx),
            owner = owner,
            modifiers = if (static) setOf(Modifier.STATIC) else emptySet(),
            origin = BINARY,
            signature = ": ${typeText(p.returnType, nr, tp)}",
        )
    }

    private fun classifierFqn(t: ProtoBuf.Type, nr: NameResolverImpl): String? =
        if (t.hasClassName()) nr.getQualifiedClassName(t.className).replace('/', '.') else null

    private fun typeRef(t: ProtoBuf.Type, nr: NameResolverImpl, tp: Map<Int, String>, ctx: KotlinTypeContext?): TypeRef? = when {
        t.hasTypeParameter() -> KotlinType(tp[t.typeParameter] ?: "T", nullable = t.nullable, context = ctx, isTypeParameter = true)
        t.hasTypeParameterName() -> KotlinType(nr.getString(t.typeParameterName), nullable = t.nullable, context = ctx, isTypeParameter = true)
        t.hasClassName() -> {
            val fqn = nr.getQualifiedClassName(t.className).replace('/', '.')
            val args = t.argumentList.mapNotNull { a ->
                if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) null else typeRef(a.type, nr, tp, ctx)
            }
            KotlinType(fqn, args, nullable = t.nullable, context = ctx)
        }
        else -> null
    }

    private fun typeText(t: ProtoBuf.Type, nr: NameResolverImpl, tp: Map<Int, String>): String = when {
        t.hasTypeParameter() -> (tp[t.typeParameter] ?: "T") + if (t.nullable) "?" else ""
        t.hasTypeParameterName() -> nr.getString(t.typeParameterName) + if (t.nullable) "?" else ""
        t.hasClassName() -> {
            val fqn = nr.getQualifiedClassName(t.className).replace('/', '.')
            val args = t.argumentList.map { a ->
                if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) "*" else typeText(a.type, nr, tp)
            }
            TypeRendering.render(fqn, args, t.nullable)
        }
        else -> "?"
    }

    private fun load(): Map<String, Pair<ProtoBuf.Class, NameResolverImpl>> {
        val out = HashMap<String, Pair<ProtoBuf.Class, NameResolverImpl>>()
        for (jar in jars) {
            val z = runCatching { ZipFile(jar.toFile()) }.getOrNull() ?: continue
            z.use {
                val entries = it.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".kotlin_builtins")) continue
                    runCatching {
                        val bytes = it.getInputStream(e).use { s -> s.readBytes() }
                        val stream = ByteArrayInputStream(bytes)
                        BuiltInsBinaryVersion.readFrom(stream) // consume the version header
                        val frag = ProtoBuf.PackageFragment.parseFrom(stream, BuiltInSerializerProtocol.extensionRegistry)
                        val nr = NameResolverImpl(frag.strings, frag.qualifiedNames)
                        frag.class_List.forEach { c -> out[nr.getQualifiedClassName(c.fqName).replace('/', '.')] = c to nr }
                    }
                }
            }
        }
        return out
    }

    private companion object {
        val BINARY = SymbolOrigin(fromSource = false, file = null)
    }
}
