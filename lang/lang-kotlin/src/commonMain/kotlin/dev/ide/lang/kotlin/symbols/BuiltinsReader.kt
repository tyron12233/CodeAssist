package dev.ide.lang.kotlin.symbols

import dev.ide.platform.FileSource
import dev.ide.kotlin.classfile.KotlinBuiltins
import dev.ide.kotlin.classfile.KotlinClassInfo
import dev.ide.kotlin.classfile.KotlinClassKind
import dev.ide.kotlin.classfile.KotlinDeclaration
import dev.ide.kotlin.classfile.KotlinModality
import dev.ide.kotlin.classfile.KotlinParameter
import dev.ide.kotlin.classfile.KotlinTypeArgument
import dev.ide.kotlin.classfile.KotlinTypeParameter
import dev.ide.kotlin.classfile.KotlinVariance
import dev.ide.kotlin.classfile.KotlinVisibility
import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.platform.openFile
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import dev.ide.kotlin.classfile.KotlinType as MetadataType

/**
 * Decodes Kotlin's built-in declarations (`List`, `Map`, `Int`, `String`, `Collection`, etc.) from the
 * `.kotlin_builtins` binaries shipped in `kotlin-stdlib.jar` (`kotlin/collections/collections.kotlin_builtins`,
 * `kotlin/kotlin.kotlin_builtins`, etc.). These are the actual Kotlin types, so they need not be approximated
 * via `java.util.List`/`java.lang.Integer` (which over-included mutating + static methods). A read-only
 * `List` has exactly its Kotlin members (no `add`/`remove`), and `Int.` shows its companion's `MAX_VALUE`.
 *
 * The decode goes through [KotlinBuiltins], which reads the format with no JVM behind it — the same reader
 * the classpath's `@Metadata` goes through, differing only in where the name tables come from. Companion-object
 * members are tagged [Modifier.STATIC] so the instance/type member filter shows them only on type access
 * (`Int.`).
 *
 * The decode produces a context-free [TypeShape] per type, so the SAME shape is what the `kotlin.builtins`
 * index ([dev.ide.lang.kotlin.index.KotlinBuiltinsIndex]) persists — [shapesFrom] is the shared producer. This
 * live reader is used only when NO index is wired (standalone / tests); the IDE queries the index instead.
 */
class BuiltinsReader(private val jars: List<String>) {

    private val byFqn: Map<String, TypeShape> by lazy { load() }

    fun isBuiltin(fqn: String): Boolean = fqn in byFqn

    /** The built-in shape for [fqn] (members + companion-as-static + supertypes + type params), rebound to
     *  [ctx] so its contained types resolve again; null if [fqn] is not a Kotlin built-in. */
    fun lookup(fqn: String, ctx: KotlinTypeContext?): TypeShape? = byFqn[fqn]?.withContext(ctx)

    /** Every TOP-LEVEL/extension callable declared in the jars' `.kotlin_builtins` fragments — the compiler
     *  intrinsics (`arrayOf`, `intArrayOf`, `emptyArray`, …) that have NO `.class` file. Used only when NO
     *  index is wired (standalone / tests); the IDE reads them from the `kotlin.builtinCallables` index. */
    fun topLevelCallables(): List<KotlinSymbol> = callables

    private val callables: List<KotlinSymbol> by lazy {
        val out = ArrayList<KotlinSymbol>()
        forEachFragment { path, bytes -> out += callablesFrom(bytes, packageOf(path)) }
        out
    }

    private fun load(): Map<String, TypeShape> {
        val out = HashMap<String, TypeShape>()
        forEachFragment { _, bytes -> out.putAll(shapesFrom(bytes)) }
        return out
    }

    /** Hand every `.kotlin_builtins` entry in every jar to [block], as (entry path, bytes). */
    private inline fun forEachFragment(block: (String, ByteArray) -> Unit) {
        for (jar in jars) {
            val source: FileSource = openFile(jar) ?: continue
            try {
                val archive = ZipArchive.open(source) ?: continue
                for (entry in archive.entries) {
                    if (!entry.name.endsWith(SUFFIX)) continue
                    val bytes = archive.read(entry) ?: continue
                    block(entry.name, bytes)
                }
            } finally {
                source.close()
            }
        }
    }

    /** The Kotlin package a fragment declares, from its path (`kotlin/collections/…` → `kotlin.collections`). */
    private fun packageOf(entryPath: String): String =
        entryPath.substringBeforeLast('/', "").replace('/', '.')

    companion object {
        private const val SUFFIX = ".kotlin_builtins"

        private val BINARY = SymbolOrigin(fromSource = false, file = null)

        /** Decode one `.kotlin_builtins` package fragment into context-free [TypeShape]s, keyed by Kotlin FQN
         *  (`kotlin.collections.List`). Companion-object members are merged into the owner as statics, exactly
         *  as a `Type.` reference sees them. The shared producer for the live reader AND the persistent index. */
        fun shapesFrom(bytes: ByteArray): Map<String, TypeShape> {
            val fragment = KotlinBuiltins.read(bytes) ?: return emptyMap()
            val classes = fragment.classes
            val out = HashMap<String, TypeShape>(classes.size)
            for ((fqn, cls) in classes) {
                val owner = KotlinSymbol(fqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
                val members = ArrayList<KotlinSymbol>()
                cls.declarations.asSequence()
                    .filter { it.receiverType == null }
                    .forEach { member(it, owner, static = false)?.let(members::add) }
                // Enum ENTRIES (`AnnotationTarget.CLASS`, `AnnotationRetention.SOURCE`, `DeprecationLevel.WARNING`).
                // A builtin enum is declared ONLY in the `.kotlin_builtins` fragment, where its entries live in
                // their own protobuf list — they are neither functions nor properties, so without this the type
                // decoded with zero constants: `AnnotationTarget.CLASS` resolved to nothing (a false "Unresolved
                // reference") and `AnnotationTarget.` completed empty. A `.class`-backed enum never had the
                // problem — the bytecode reader surfaces its entries as static fields of the enum's own type.
                for (entry in cls.enumEntryNames) {
                    members += KotlinSymbol(
                        name = entry,
                        kind = SymbolKind.ENUM_CONSTANT,
                        type = KotlinType(fqn),
                        owner = owner,
                        modifiers = setOf(Modifier.STATIC), // reached through the TYPE, like a companion member
                        origin = BINARY,
                        signature = ": ${TypeRendering.render(fqn, emptyList(), false)}",
                        declaringClassFqn = fqn,
                    )
                }
                // Companion object: its members behave like statics (`Int.MAX_VALUE`).
                val companionName = cls.companionObjectName
                companionName?.let { name ->
                    classes["$fqn.$name"]?.declarations?.asSequence()
                        ?.filter { it.receiverType == null }
                        ?.forEach { member(it, owner, static = true)?.let(members::add) }
                }
                val kind = cls.classKind
                val modality = cls.modality
                out[fqn] = TypeShape(
                    typeParameters = cls.typeParameters.map { it.name },
                    typeParameterBounds = emptyList(),
                    typeParameterVariances = cls.typeParameters.map { varianceStr(it.variance) },
                    supertypes = cls.supertypes.mapNotNull { typeRef(it) },
                    members = members,
                    isObject = kind == KotlinClassKind.OBJECT,
                    companionObjectName = companionName,
                    isKotlin = true,
                    isInterface = kind == KotlinClassKind.INTERFACE,
                    isAbstract = modality == KotlinModality.ABSTRACT || modality == KotlinModality.SEALED,
                    // A plain final builtin class (`String`, `Int`, …): kind CLASS + modality FINAL. Extending one
                    // is FINAL_SUPERTYPE, same as the @Metadata path; enum/annotation/object kinds are excluded.
                    isFinalClass = kind == KotlinClassKind.CLASS && modality == KotlinModality.FINAL,
                )
            }
            return out
        }

        /** A class MEMBER: a function or a property. A constructor or a nested type alias is not one. */
        private fun member(d: KotlinDeclaration, owner: KotlinSymbol, static: Boolean): KotlinSymbol? = when (d.kind) {
            KotlinDeclaration.Kind.FUNCTION -> func(d, owner, static)
            KotlinDeclaration.Kind.PROPERTY -> prop(d, owner, static)
            else -> null
        }

        private fun func(f: KotlinDeclaration, owner: KotlinSymbol, static: Boolean): KotlinSymbol {
            val params = f.parameters.joinToString(", ") { "${it.name}: ${typeText(it.effectiveType)}" }
            return KotlinSymbol(
                name = f.name,
                kind = SymbolKind.METHOD,
                type = typeRef(f.returnType),
                owner = owner,
                modifiers = (if (static) setOf(Modifier.STATIC) else emptySet()) + abstractFlag(f),
                origin = BINARY,
                signature = "($params): ${typeText(f.returnType)}",
                typeParameters = f.typeParameters.map { it.name },
                paramTypes = f.parameters.map { typeRef(it.effectiveType) },
                isInfix = f.isInfix,
                varargParamIndex = f.parameters.indexOfFirst { it.isVararg },
            )
        }

        private fun prop(p: KotlinDeclaration, owner: KotlinSymbol, static: Boolean): KotlinSymbol =
            KotlinSymbol(
                name = p.name,
                kind = SymbolKind.FIELD,
                type = typeRef(p.returnType),
                owner = owner,
                modifiers = (if (static) setOf(Modifier.STATIC) else emptySet()) + abstractFlag(p),
                origin = BINARY,
                signature = ": ${typeText(p.returnType)}",
            )

        /** ABSTRACT modality on a builtin member (e.g. `Iterator.next`, `Comparable.compareTo`) → [Modifier.ABSTRACT],
         *  so implementing a builtin interface requires an override. A member with a default body decodes OPEN. */
        private fun abstractFlag(d: KotlinDeclaration): Set<Modifier> =
            if (d.modality == KotlinModality.ABSTRACT) setOf(Modifier.ABSTRACT) else emptySet()

        /**
         * Decode the TOP-LEVEL (package-level) callables of one `.kotlin_builtins` fragment — the compiler
         * INTRINSICS with NO `.class` file (`arrayOf`, `intArrayOf`, `charArrayOf`, `emptyArray`, …), so neither
         * [shapesFrom] (types only) nor the `.class`-scanning `KotlinCallableIndex` ever surfaces them.
         * [packageName] is the fragment's Kotlin package (`kotlin`, `kotlin.collections`, …), which the caller
         * derives from the entry path. A declaration with a receiver is emitted as an extension (receiver set);
         * the rest as top-level. Context-free like [shapesFrom] — the consumer rebinds the context.
         */
        fun callablesFrom(bytes: ByteArray, packageName: String): List<KotlinSymbol> {
            val fragment = KotlinBuiltins.read(bytes) ?: return emptyList()
            val out = ArrayList<KotlinSymbol>(fragment.topLevel.declarations.size)
            for (d in fragment.topLevel.declarations) {
                when (d.kind) {
                    KotlinDeclaration.Kind.FUNCTION -> out += pkgFunc(d, packageName)
                    KotlinDeclaration.Kind.PROPERTY -> out += pkgProp(d, packageName)
                    else -> {}
                }
            }
            return out
        }

        private fun pkgFunc(f: KotlinDeclaration, pkg: String): KotlinSymbol {
            val receiver = f.receiverType
            val params = f.parameters.joinToString(", ") { "${it.name}: ${typeText(it.effectiveType)}" }
            return KotlinSymbol(
                name = f.name,
                kind = SymbolKind.METHOD,
                type = typeRef(f.returnType),
                modifiers = visibilityMods(f.visibility),
                origin = BINARY,
                receiverTypeFqn = receiver?.let(::receiverFqnOf),
                signature = "($params): ${typeText(f.returnType)}",
                typeParameters = f.typeParameters.map { it.name },
                paramTypes = f.parameters.map { typeRef(it.effectiveType) },
                paramNames = f.parameters.map { it.name },
                receiverTypeArgs = receiverArgs(receiver),
                receiverTypeParam = receiver?.let(::receiverTypeParamOf),
                packageName = pkg,
                isInternal = f.visibility == KotlinVisibility.INTERNAL,
                isInline = f.isInline,
                isInfix = f.isInfix,
                isSuspend = f.isSuspend,
                varargParamIndex = f.parameters.indexOfFirst { it.isVararg },
                paramHasDefault = f.parameters.map { it.declaresDefaultValue },
            )
        }

        private fun pkgProp(p: KotlinDeclaration, pkg: String): KotlinSymbol {
            val receiver = p.receiverType
            return KotlinSymbol(
                name = p.name,
                kind = SymbolKind.FIELD,
                type = typeRef(p.returnType),
                modifiers = visibilityMods(p.visibility),
                origin = BINARY,
                receiverTypeFqn = receiver?.let(::receiverFqnOf),
                signature = ": ${typeText(p.returnType)}",
                typeParameters = p.typeParameters.map { it.name },
                receiverTypeArgs = receiverArgs(receiver),
                receiverTypeParam = receiver?.let(::receiverTypeParamOf),
                packageName = pkg,
                isInternal = p.visibility == KotlinVisibility.INTERNAL,
            )
        }

        /** A `vararg` parameter's declared type is the ARRAY; what one argument may be is the element type. */
        private val KotlinParameter.effectiveType: MetadataType?
            get() = varargElementType ?: type

        private fun receiverArgs(receiver: MetadataType?): List<TypeRef> =
            receiver?.arguments.orEmpty().mapNotNull { a -> if (a.isStar) null else typeRef(a.type) }

        /** A builtin type parameter's declaration-site variance as the model string (`"out"`/`"in"`/`""`). */
        private fun varianceStr(v: KotlinVariance): String = when (v) {
            KotlinVariance.OUT -> "out"
            KotlinVariance.IN -> "in"
            KotlinVariance.INVARIANT -> ""
        }

        /** The receiver's class FQN for an extension; a bare type-parameter receiver keys on `kotlin.Any`. */
        private fun receiverFqnOf(t: MetadataType): String? = when {
            !t.isTypeParameter -> t.classifier
            else -> "kotlin.Any"
        }

        private fun receiverTypeParamOf(t: MetadataType): String? =
            if (t.isTypeParameter) t.classifier else null

        private fun visibilityMods(v: KotlinVisibility?): Set<Modifier> = when (v) {
            KotlinVisibility.PRIVATE, KotlinVisibility.PRIVATE_TO_THIS, KotlinVisibility.LOCAL -> setOf(Modifier.PRIVATE)
            KotlinVisibility.PROTECTED -> setOf(Modifier.PROTECTED)
            else -> emptySet()
        }

        private fun typeRef(t: MetadataType?): TypeRef? {
            if (t == null) return null
            if (t.isTypeParameter) {
                return KotlinType(t.classifier, nullable = t.isNullable, context = null, isTypeParameter = true)
            }
            val args = t.arguments.mapNotNull { a ->
                if (a.isStar) null
                else (typeRef(a.type) as? KotlinType)?.let { base ->
                    when (a.variance) { // capture the USE-SITE projection (`Array<out T>`, `in T`)
                        KotlinVariance.OUT -> base.withProjection("out")
                        KotlinVariance.IN -> base.withProjection("in")
                        KotlinVariance.INVARIANT -> base
                    }
                }
            }
            return KotlinType(t.classifier, args, nullable = t.isNullable, context = null)
        }

        private fun typeText(t: MetadataType?): String {
            if (t == null) return "?"
            if (t.isTypeParameter) return t.classifier + if (t.isNullable) "?" else ""
            val args = t.arguments.map { a -> if (a.isStar) "*" else typeText(a.type) }
            return TypeRendering.render(t.classifier, args, t.isNullable)
        }
    }
}
