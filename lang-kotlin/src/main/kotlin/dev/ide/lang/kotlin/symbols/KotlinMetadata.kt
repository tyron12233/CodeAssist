package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import kotlin.metadata.ClassKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.kind
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isInline
import kotlin.metadata.isNullable
import kotlin.metadata.visibility
import org.objectweb.asm.MethodVisitor
import kotlin.metadata.isSuspend
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations

/**
 * Decodes a classpath `.class` file's `@kotlin.Metadata` into neutral symbols. Recovers the Kotlin view
 * that bytecode erases: extension functions, properties-as-properties, nullability, and type parameters
 * (kept as [KotlinType.isTypeParameter] refs) plus the value-param types and the extension receiver's type
 * arguments, so the inference engine can substitute them.
 */
object KotlinMetadata {

    /** A decoded unit: a class (own members + supertypes + type params) or a file/multifile facade. */
    class Decoded(
        val classFqn: String?,
        /** Generic supertypes carrying their type arguments (`ProvidableCompositionLocal<T>` → `CompositionLocal<T>`),
         *  so a member inherited through a generic supertype substitutes (the `current: T` → `TextStyle` case). */
        val supertypes: List<TypeRef>,
        /** The class's own type-parameter names (`List<T>` → `["T"]`), for member substitution. */
        val typeParameters: List<String>,
        val ownMembers: List<KotlinSymbol>,
        val topLevel: List<KotlinSymbol>,
        val extensions: List<KotlinSymbol>,
        /** For a multi-file class PART, the public FACADE class FQN (`kotlin.math.MathKt`) the part's
         *  top-level functions are actually invoked through — not the part's own `…__…Kt` name. Null for a
         *  plain file facade (its own class name, set by the reader, is correct). */
        val facadeClassFqn: String? = null,
        /** The simple name of this class's companion object (`"Companion"` by default), or null if none. A
         *  bare `Type.` reference resolves to the companion instance, so extensions applicable to it apply. */
        val companionObjectName: String? = null,
        /** True when this class is a Kotlin `object` singleton (`CardDefaults`, `MaterialTheme`). A bare
         *  reference to it (`CardDefaults.`) denotes the INSTANCE, so its members are accessed like an
         *  instance's — not statics off a type. */
        val isObject: Boolean = false,
    ) {
        /** Just the supertype classifier FQNs — for the supertype walk that doesn't need type arguments. */
        val supertypeFqns: List<String> get() = supertypes.mapNotNull { (it as? KotlinType)?.qualifiedName }
    }

    fun isKotlin(classBytes: ByteArray): Boolean = extract(classBytes) != null

    /**
     * Whether [classBytes] is a Kotlin **file/multi-file facade** or **synthetic** JVM class (`FooKt`,
     * `StringsKt`, `StringsKt__StringsJVMKt`, lambda/`$WhenMappings` classes) — a class that holds top-level
     * callables or compiler-synthesized members, NOT a user-referenceable Kotlin type. `false` for a real
     * `class`/`object`/`interface`/`enum`/`annotation` and for plain (non-Kotlin) bytecode. Lets class-name
     * completion drop facades the bytecode-name-only `java.classNames` index can't distinguish.
     */
    fun isFacadeOrSynthetic(classBytes: ByteArray): Boolean {
        val metadata = extract(classBytes) ?: return false
        return when (runCatching { KotlinClassMetadata.readLenient(metadata) }.getOrNull()) {
            is KotlinClassMetadata.FileFacade,
            is KotlinClassMetadata.MultiFileClassFacade,
            is KotlinClassMetadata.MultiFileClassPart,
            is KotlinClassMetadata.SyntheticClass -> true
            else -> false // a real Class, an Unknown/newer-version blob, or unparseable → keep it
        }
    }

    /** One-shot guard so a decode failure (e.g. `kotlin-metadata-jvm` missing on ART) is logged once, not per class. */
    private val loggedDecodeFailure = java.util.concurrent.atomic.AtomicBoolean(false)

    fun decode(classBytes: ByteArray, ctx: KotlinTypeContext?): Decoded? {
        val metadata = extract(classBytes) ?: return null
        // `@Composable` isn't in the @Metadata blob; detect it from the bytecode (the annotation and/or the
        // synthetic `Composer` parameter the plugin appends), correlated to the metadata function by name.
        val composable = composableMethodNames(classBytes)
        val kmResult = runCatching { KotlinClassMetadata.readLenient(metadata) }
        // DIAGNOSTIC: if reading the @Metadata blob throws (a missing kotlin-metadata-jvm class on ART, or an
        // unparseable blob), every Kotlin library symbol silently vanishes — log the first occurrence with the
        // real cause so a device-only "0 candidates" is explained.
        kmResult.exceptionOrNull()?.let { e ->
            if (loggedDecodeFailure.compareAndSet(false, true))
                dev.ide.platform.log.Log.logger("kotlin.metadata")
                    .warn("readLenient failed (first occurrence): ${e.javaClass.name}: ${e.message}", e)
        }
        return when (val km = kmResult.getOrNull()) {
            is KotlinClassMetadata.Class -> decodeClass(km.kmClass, ctx, composable)
            is KotlinClassMetadata.FileFacade -> decodePackage(km.kmPackage, ctx, null, composable)
            is KotlinClassMetadata.MultiFileClassPart -> decodePackage(km.kmPackage, ctx, km.facadeClassName.replace('/', '.'), composable)
            else -> null
        }
    }

    /** JVM method names that are `@Composable`: either carry the annotation or take a `Composer` parameter
     *  (the plugin appends one). Read straight from the bytecode — the Kotlin metadata doesn't store it. */
    private fun composableMethodNames(bytes: ByteArray): Set<String> {
        val names = HashSet<String>()
        val reader = runCatching { ClassReader(bytes) }.getOrNull() ?: return names
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, sig: String?, exceptions: Array<out String>?): MethodVisitor? {
                if (COMPOSER_DESC in descriptor) names += name
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(desc: String?, visible: Boolean): AnnotationVisitor? {
                        if (desc == COMPOSABLE_ANNO_DESC) names += name
                        return null
                    }
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        return names
    }

    private fun decodeClass(km: KmClass, ctx: KotlinTypeContext?, composable: Set<String>): Decoded {
        val classFqn = km.name.replace('/', '.')
        val owner = KotlinSymbol(classFqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
        val classTp = km.typeParameters.associate { it.id to it.name }
        val own = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()
        km.functions.forEach { f -> funcSymbol(f, ctx, owner, classTp, classFqn, composable).let { if (it.isExtension) ext += it else own += it } }
        km.properties.forEach { p -> propSymbol(p, ctx, owner, classTp, classFqn).let { if (it.isExtension) ext += it else own += it } }
        km.constructors.forEach { c ->
            own += KotlinSymbol(
                name = classFqn.substringAfterLast('.'),
                kind = SymbolKind.CONSTRUCTOR,
                type = ctx?.let { KotlinType(classFqn, context = it) },
                owner = owner,
                origin = BINARY,
                signature = "(" + c.valueParameters.joinToString(", ") { vp -> "${vp.name}: ${typeText(vp.type, classTp)}" } + ")",
                paramTypes = c.valueParameters.map { vp -> typeRef(vp.varargElementType ?: vp.type, ctx, classTp) },
                paramNames = c.valueParameters.map { it.name },
                declaringClassFqn = classFqn,
                varargParamIndex = c.valueParameters.indexOfFirst { it.varargElementType != null },
            )
        }
        // Enum entries are accessed statically off the enum type (`Color.RED`); surface them as static fields
        // typed to the enum, so expected-type completion can offer them.
        km.enumEntries.forEach { entry ->
            own += KotlinSymbol(
                name = entry,
                kind = SymbolKind.ENUM_CONSTANT,
                type = ctx?.let { KotlinType(classFqn, context = it) },
                owner = owner,
                modifiers = setOf(Modifier.STATIC),
                origin = BINARY,
            )
        }
        return Decoded(classFqn, km.supertypes.mapNotNull { typeRef(it, ctx, classTp) }, km.typeParameters.map { it.name }, own, emptyList(), ext, companionObjectName = km.companionObject, isObject = km.kind == ClassKind.OBJECT)
    }

    private fun decodePackage(km: KmPackage, ctx: KotlinTypeContext?, facadeFqn: String?, composable: Set<String>): Decoded {
        val top = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()
        // The facade class FQN isn't in KmPackage for a plain file facade — the ClasspathReader sets
        // declaringClassFqn from the .class entry name. For a multi-file class PART, [facadeFqn] overrides it
        // (the part's `…__…Kt` name isn't where the public static method lives).
        km.functions.forEach { f -> funcSymbol(f, ctx, null, emptyMap(), facadeFqn, composable).let { if (it.isExtension) ext += it else top += it } }
        km.properties.forEach { p -> propSymbol(p, ctx, null, emptyMap(), facadeFqn).let { if (it.isExtension) ext += it else top += it } }
        return Decoded(null, emptyList(), emptyList(), emptyList(), top, ext, facadeClassFqn = facadeFqn)
    }

    private fun funcSymbol(f: KmFunction, ctx: KotlinTypeContext?, owner: KotlinSymbol?, classTp: Map<Int, String>, declaringFqn: String?, composable: Set<String>): KotlinSymbol {
        val tp = classTp + f.typeParameters.associate { it.id to it.name }
        val receiver = f.receiverParameterType
        val (recvFqn, recvParam) = receiver?.let { receiverInfo(it, f.typeParameters) } ?: (null to null)
        val params = f.valueParameters.joinToString(", ") { vp -> "${vp.name}: ${typeText(vp.varargElementType ?: vp.type, tp)}" }
        return KotlinSymbol(
            name = f.name,
            kind = SymbolKind.METHOD,
            type = typeRef(f.returnType, ctx, tp),
            owner = owner,
            modifiers = visibilityMods(f.visibility),
            isInternal = f.visibility == Visibility.INTERNAL,
            origin = BINARY,
            receiverTypeFqn = recvFqn,
            signature = "($params): ${typeText(f.returnType, tp)}",
            typeParameters = f.typeParameters.map { it.name },
            typeParamBoundNames = f.typeParameters.map { siblingBoundName(it, tp) },
            paramTypes = f.valueParameters.map { typeRef(it.varargElementType ?: it.type, ctx, tp) },
            paramNames = f.valueParameters.map { it.name },
            receiverTypeArgs = receiver?.arguments?.map { arg -> arg.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx) } ?: emptyList(),
            receiverTypeParam = recvParam,
            declaringClassFqn = declaringFqn,
            isComposable = f.name in composable,
            isInline = f.isInline,
            isSuspend = f.isSuspend,
            varargParamIndex = f.valueParameters.indexOfFirst { it.varargElementType != null },
            paramHasDefault = f.valueParameters.map { it.declaresDefaultValue },
        )
    }

    private fun propSymbol(p: KmProperty, ctx: KotlinTypeContext?, owner: KotlinSymbol?, classTp: Map<Int, String>, declaringFqn: String?): KotlinSymbol {
        val tp = classTp + p.typeParameters.associate { it.id to it.name }
        val receiver = p.receiverParameterType
        val (recvFqn, recvParam) = receiver?.let { receiverInfo(it, p.typeParameters) } ?: (null to null)
        return KotlinSymbol(
            name = p.name,
            kind = SymbolKind.FIELD,
            type = typeRef(p.returnType, ctx, tp),
            owner = owner,
            modifiers = visibilityMods(p.visibility),
            isInternal = p.visibility == Visibility.INTERNAL,
            origin = BINARY,
            receiverTypeFqn = recvFqn,
            signature = ": ${typeText(p.returnType, tp)}",
            receiverTypeArgs = receiver?.arguments?.map { arg -> arg.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx) } ?: emptyList(),
            receiverTypeParam = recvParam,
            declaringClassFqn = declaringFqn,
        )
    }

    /** When [param]'s upper bound is a SIBLING type parameter (`fun <R, T : R>` → T's bound is R), that
     *  parameter's name (looked up in the id→name map [tp]); null when the bound is a class/absent. Drives
     *  `T : R` constraint propagation (see [KotlinSymbol.typeParamBoundNames]). */
    private fun siblingBoundName(param: KmTypeParameter, tp: Map<Int, String>): String? =
        param.upperBounds.firstNotNullOfOrNull { ub -> (ub.classifier as? KmClassifier.TypeParameter)?.let { tp[it.id] } }

    /** For an extension receiver: (keying FQN, type-param name if the receiver is a bare type parameter).
     *  `T.also` keys by T's upper bound (`kotlin.Any` if unbounded) and remembers `T` to bind to the receiver. */
    private fun receiverInfo(receiver: KmType, declTypeParams: List<KmTypeParameter>): Pair<String?, String?> =
        when (val c = receiver.classifier) {
            is KmClassifier.Class -> c.name.replace('/', '.') to null
            is KmClassifier.TypeAlias -> c.name.replace('/', '.') to null
            is KmClassifier.TypeParameter -> {
                val decl = declTypeParams.firstOrNull { it.id == c.id }
                val bound = decl?.upperBounds?.firstNotNullOfOrNull { classifierFqn(it) } ?: "kotlin.Any"
                bound to decl?.name
            }
        }

    // --- KmType helpers ---

    fun classifierFqn(t: KmType): String? = when (val c = t.classifier) {
        is KmClassifier.Class -> c.name.replace('/', '.')
        is KmClassifier.TypeAlias -> c.name.replace('/', '.')
        is KmClassifier.TypeParameter -> null
    }

    private fun typeRef(t: KmType, ctx: KotlinTypeContext?, tp: Map<Int, String>): TypeRef? {
        val classifier = t.classifier
        if (classifier is KmClassifier.TypeParameter) {
            val name = tp[classifier.id] ?: "T"
            return KotlinType(name, nullable = t.isNullable, context = ctx, isTypeParameter = true)
        }
        val fqn = classifierFqn(t) ?: return null
        val args = t.arguments.map { arg -> arg.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx) }
        // `T.() -> R` (apply/with/run blocks, DSL builders) carries @kotlin.ExtensionFunctionType on the type;
        // a Compose content slot (`@Composable () -> Unit`) carries @androidx.compose.runtime.Composable on it.
        val annos = if (TypeRendering.isFunctionType(fqn))
            runCatching { t.annotations.map { it.className } }.getOrDefault(emptyList()) else emptyList()
        val isExtFn = "kotlin/ExtensionFunctionType" in annos
        val isComposable = "androidx/compose/runtime/Composable" in annos
        // A `suspend (…) -> R` parameter is flagged `isSuspend` but stored in its JVM-lowered shape: classifier
        // `FunctionN` with a trailing `Continuation<R>` value parameter and an erased `Any` return. Rewrite it to
        // the source shape `kotlin.SuspendFunction{N-1}` (drop the continuation, recover R from `Continuation<R>`)
        // so binary suspend types match the source representation: the suspend calling-convention check, type
        // rendering, and the FQN flow through the persistent caches all then treat them uniformly.
        if (t.isSuspend && TypeRendering.isFunctionType(fqn) && args.size >= 2) {
            return desugarSuspendFunctionType(args, t.isNullable, ctx, isExtFn)
        }
        return KotlinType(fqn, args, nullable = t.isNullable, context = ctx, isExtensionFunctionType = isExtFn, isComposable = isComposable)
    }

    /** Convert a JVM-lowered suspend function type's [loweredArgs] (`[p0, …, p{k-1}, Continuation<R>, Any]`) into
     *  the source-level `kotlin.SuspendFunction{k}` with arguments `[p0, …, p{k-1}, R]`. The continuation is the
     *  last value parameter (index `size - 2`; the final entry is the erased `Any` return), and the real return
     *  `R` is its sole type argument. A receiver function type keeps `p0` as the receiver via [isExtFn]. */
    private fun desugarSuspendFunctionType(
        loweredArgs: List<TypeRef>,
        nullable: Boolean,
        ctx: KotlinTypeContext?,
        isExtFn: Boolean,
    ): KotlinType {
        val continuation = loweredArgs[loweredArgs.size - 2]
        val realReturn = (continuation as? KotlinType)?.typeArguments?.firstOrNull() ?: KotlinType("kotlin.Unit", context = ctx)
        val realParams = loweredArgs.subList(0, loweredArgs.size - 2)
        val newArgs = realParams + realReturn
        return KotlinType(
            "kotlin.SuspendFunction${realParams.size}", newArgs, nullable = nullable, context = ctx,
            isExtensionFunctionType = isExtFn,
        )
    }

    private fun typeText(t: KmType, tp: Map<Int, String>): String {
        val classifier = t.classifier
        if (classifier is KmClassifier.TypeParameter) {
            return (tp[classifier.id] ?: "T") + if (t.isNullable) "?" else ""
        }
        val fqn = classifierFqn(t) ?: return "?"
        val args = t.arguments.map { arg -> arg.type?.let { typeText(it, tp) } ?: "*" }
        return TypeRendering.render(fqn, args, t.isNullable)
    }

    private val BINARY = SymbolOrigin(fromSource = false, file = null)
    private const val COMPOSABLE_ANNO_DESC = "Landroidx/compose/runtime/Composable;"
    private const val COMPOSER_DESC = "Landroidx/compose/runtime/Composer;"

    private fun visibilityMods(v: Visibility): Set<Modifier> = when (v) {
        Visibility.PRIVATE, Visibility.PRIVATE_TO_THIS, Visibility.LOCAL -> setOf(Modifier.PRIVATE)
        Visibility.PROTECTED -> setOf(Modifier.PROTECTED)
        else -> emptySet()
    }

    /** Pull the `@kotlin.Metadata` annotation values off the class with ASM, or null if absent. */
    private fun extract(classBytes: ByteArray): Metadata? {
        var found = false
        var k = 1
        var mv = IntArray(0)
        var xs = ""
        var pn = ""
        var xi = 0
        val d1 = ArrayList<String>()
        val d2 = ArrayList<String>()
        val reader = runCatching { ClassReader(classBytes) }.getOrNull() ?: return null
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor != "Lkotlin/Metadata;") return null
                found = true
                return object : AnnotationVisitor(Opcodes.ASM9) {
                    override fun visit(name: String?, value: Any?) {
                        when (name) {
                            "k" -> k = value as? Int ?: k
                            "mv" -> mv = value as? IntArray ?: mv
                            "xs" -> xs = value as? String ?: xs
                            "pn" -> pn = value as? String ?: pn
                            "xi" -> xi = value as? Int ?: xi
                        }
                    }

                    override fun visitArray(name: String?): AnnotationVisitor? = when (name) {
                        "d1" -> stringCollector(d1)
                        "d2" -> stringCollector(d2)
                        else -> null
                    }
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        if (!found) return null
        return Metadata(
            kind = k,
            metadataVersion = mv,
            data1 = d1.toTypedArray(),
            data2 = d2.toTypedArray(),
            extraString = xs,
            packageName = pn,
            extraInt = xi,
        )
    }

    private fun stringCollector(into: MutableList<String>) = object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) { (value as? String)?.let { into.add(it) } }
    }
}
