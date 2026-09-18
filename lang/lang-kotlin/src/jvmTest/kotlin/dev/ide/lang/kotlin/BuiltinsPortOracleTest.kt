package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.symbols.BuiltinsReader
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.kotlin.symbols.TypeShape
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
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The differential behind taking `.kotlin_builtins` off the Kotlin compiler's protobuf classes.
 *
 * [BuiltinsReader] used to decode the fragments with `org.jetbrains.kotlin.metadata.*` — generated protobuf
 * readers that exist only on the JVM, which is what pinned the whole symbol layer there. It now goes through
 * `:kotlin-classfile`, and the claim that has to hold is not "it decodes something" but "it decodes the SAME
 * thing": every built-in type, every member, every rendered signature, every flag.
 *
 * So the OLD implementation lives on here, verbatim, as the oracle. A rewrite of a decoder is measured
 * against what it replaced, and every one of the shapes below feeds completion and resolution for `List`,
 * `String` and `Int` — the types every Kotlin file uses and no `.class` file describes.
 */
class BuiltinsPortOracleTest {

    @Test
    fun everyBuiltinTypeDecodesExactlyAsTheCompilersOwnReaderDoes() {
        val fragments = builtinFragments()
        assertTrue(fragments.size >= 5, "the stdlib carries a fragment per builtin package (got ${fragments.size})")

        var types = 0
        var members = 0
        for ((path, bytes) in fragments) {
            val theirs = Oracle.shapesFrom(bytes)
            val mine = BuiltinsReader.shapesFrom(bytes)
            assertEquals(theirs.keys.sorted(), mine.keys.sorted(), "the set of builtin types in $path")
            for ((fqn, expected) in theirs) {
                val actual = mine.getValue(fqn)
                assertEquals(render(expected), render(actual), "shape of $fqn in $path")
                types++
                members += expected.members.size
            }
        }
        println("builtin shapes: $types types, $members members across ${fragments.size} fragments")
        assertTrue(types > 100, "the stdlib declares far more than $types builtin types")
    }

    @Test
    fun everyBuiltinIntrinsicDecodesExactlyAsTheCompilersOwnReaderDoes() {
        var callables = 0
        for ((path, bytes) in builtinFragments()) {
            val pkg = path.substringBeforeLast('/', "").replace('/', '.')
            val theirs = Oracle.callablesFrom(bytes, pkg)
            val mine = BuiltinsReader.callablesFrom(bytes, pkg)
            assertEquals(theirs.map(::render), mine.map(::render), "top-level callables of $path")
            callables += theirs.size
        }
        println("builtin intrinsics: $callables callables")
        // A floor, not a count: the point is that the fragments carry intrinsics at all, so an empty decode
        // (which would still compare equal to an empty oracle) cannot pass. `theIntrinsicsThatHaveNoClassFile
        // AreStillThere` checks that the ones that matter are among them.
        assertTrue(callables >= 16, "`arrayOf` and friends are in here; got $callables")
    }

    /** `arrayOf` has no class file anywhere, so a regression here is invisible to every other suite. */
    @Test
    fun theIntrinsicsThatHaveNoClassFileAreStillThere() {
        val all = builtinFragments().flatMap { (path, bytes) ->
            BuiltinsReader.callablesFrom(bytes, path.substringBeforeLast('/', "").replace('/', '.'))
        }
        for (name in listOf("arrayOf", "emptyArray", "intArrayOf", "arrayOfNulls")) {
            assertTrue(all.any { it.name == name }, "$name is a compiler intrinsic and must survive the decode")
        }
    }

    // ---- the corpus -------------------------------------------------------------------------------------

    /** Every `.kotlin_builtins` entry in the kotlin-stdlib jar, as (entry path, bytes). */
    private fun builtinFragments(): List<Pair<String, ByteArray>> =
        ZipFile(stdlibJarPath().toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".kotlin_builtins") }
                .map { it.name to zip.getInputStream(it).use { s -> s.readBytes() } }
                .toList()
        }

    // ---- comparison -------------------------------------------------------------------------------------

    // Compared as text, because these are context-free shapes whose whole purpose is what they render to:
    // a field-by-field assert would pass on two shapes that produce different completion.
    private fun render(shape: TypeShape): String = buildString {
        append("<${shape.typeParameters.joinToString(",")}>")
        append(" variances=${shape.typeParameterVariances}")
        append(" supers=${shape.supertypes.map { it.toString() }.sorted()}")
        append(" object=${shape.isObject} companion=${shape.companionObjectName}")
        append(" interface=${shape.isInterface} abstract=${shape.isAbstract} final=${shape.isFinalClass}")
        append(" kotlin=${shape.isKotlin}\n")
        for (m in shape.members.map(::render).sorted()) append("  ").append(m).append('\n')
    }

    private fun render(s: KotlinSymbol): String =
        "${s.kind} ${s.name}${s.signature} mods=${s.modifiers.map { it.name }.sorted()} " +
            "tp=${s.typeParameters} params=${s.paramTypes.map { it?.toString() }} names=${s.paramNames} " +
            "recv=${s.receiverTypeFqn} recvArgs=${s.receiverTypeArgs.map { it.toString() }} " +
            "recvTp=${s.receiverTypeParam} pkg=${s.packageName} decl=${s.declaringClassFqn} " +
            "type=${s.type} internal=${s.isInternal} inline=${s.isInline} infix=${s.isInfix} " +
            "suspend=${s.isSuspend} vararg=${s.varargParamIndex} defaults=${s.paramHasDefault}"

    /**
     * [BuiltinsReader] as it was before the port: the compiler's own `ProtoBuf` readers, unchanged.
     *
     * Kept verbatim on purpose. Tidying it would make the two sides agree by construction rather than by
     * decoding the same bytes the same way, which is the only thing this proves.
     */
    private object Oracle {
        private val BINARY = SymbolOrigin(fromSource = false, file = null)

        fun shapesFrom(bytes: ByteArray): Map<String, TypeShape> = runCatching {
            val stream = ByteArrayInputStream(bytes)
            BuiltInsBinaryVersion.readFrom(stream)
            val frag = ProtoBuf.PackageFragment.parseFrom(stream, BuiltInSerializerProtocol.extensionRegistry)
            val nr = NameResolverImpl(frag.strings, frag.qualifiedNames)
            val classes = frag.class_List.associateBy { nr.getQualifiedClassName(it.fqName).replace('/', '.') }
            val out = HashMap<String, TypeShape>(classes.size)
            for ((fqn, cls) in classes) {
                val owner = KotlinSymbol(fqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
                val classTp = cls.typeParameterList.associate { it.id to nr.getString(it.name) }
                val tt = TypeTable(cls.typeTable)
                val members = ArrayList<KotlinSymbol>()
                cls.functionList.filterNot { it.hasReceiverType() }.forEach { members += func(it, nr, classTp, owner, static = false, tt = tt) }
                cls.propertyList.filterNot { it.hasReceiverType() }.forEach { members += prop(it, nr, classTp, owner, static = false, tt = tt) }
                cls.enumEntryList.forEach { e ->
                    val entry = nr.getString(e.name)
                    members += KotlinSymbol(
                        name = entry,
                        kind = SymbolKind.ENUM_CONSTANT,
                        type = KotlinType(fqn),
                        owner = owner,
                        modifiers = setOf(Modifier.STATIC),
                        origin = BINARY,
                        signature = ": ${TypeRendering.render(fqn, emptyList(), false)}",
                        declaringClassFqn = fqn,
                    )
                }
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
                    typeParameterVariances = cls.typeParameterList.map { varianceStr(it.variance) },
                    supertypes = supers,
                    members = members,
                    isObject = isObject,
                    companionObjectName = companionName,
                    isKotlin = true,
                    isInterface = Flags.CLASS_KIND.get(cls.flags) == ProtoBuf.Class.Kind.INTERFACE,
                    isAbstract = Flags.MODALITY.get(cls.flags).let { it == ProtoBuf.Modality.ABSTRACT || it == ProtoBuf.Modality.SEALED },
                    isFinalClass = Flags.CLASS_KIND.get(cls.flags) == ProtoBuf.Class.Kind.CLASS &&
                        Flags.MODALITY.get(cls.flags) == ProtoBuf.Modality.FINAL,
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

        private fun abstractFlag(flags: Int): Set<Modifier> =
            if (Flags.MODALITY.get(flags) == ProtoBuf.Modality.ABSTRACT) setOf(Modifier.ABSTRACT) else emptySet()

        fun callablesFrom(bytes: ByteArray, packageName: String): List<KotlinSymbol> = runCatching {
            val stream = ByteArrayInputStream(bytes)
            BuiltInsBinaryVersion.readFrom(stream)
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

        private fun varianceStr(v: ProtoBuf.TypeParameter.Variance): String = when (v) {
            ProtoBuf.TypeParameter.Variance.OUT -> "out"
            ProtoBuf.TypeParameter.Variance.IN -> "in"
            ProtoBuf.TypeParameter.Variance.INV -> ""
        }

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
                val args = t.argumentList.mapNotNull { a ->
                    if (a.projection == ProtoBuf.Type.Argument.Projection.STAR) null
                    else (a.type(tt)?.let { typeRef(it, nr, tp, tt) } as? KotlinType)?.let { base ->
                        when (a.projection) {
                            ProtoBuf.Type.Argument.Projection.OUT -> base.withProjection("out")
                            ProtoBuf.Type.Argument.Projection.IN -> base.withProjection("in")
                            else -> base
                        }
                    }
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
