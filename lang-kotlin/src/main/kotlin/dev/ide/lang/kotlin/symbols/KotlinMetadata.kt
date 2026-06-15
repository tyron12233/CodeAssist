package dev.ide.lang.kotlin.symbols

import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.Visibility
import kotlin.metadata.isNullable
import kotlin.metadata.visibility
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
        val supertypeFqns: List<String>,
        /** The class's own type-parameter names (`List<T>` → `["T"]`), for member substitution. */
        val typeParameters: List<String>,
        val ownMembers: List<KotlinSymbol>,
        val topLevel: List<KotlinSymbol>,
        val extensions: List<KotlinSymbol>,
    )

    fun isKotlin(classBytes: ByteArray): Boolean = extract(classBytes) != null

    fun decode(classBytes: ByteArray, ctx: KotlinTypeContext?): Decoded? {
        val metadata = extract(classBytes) ?: return null
        return when (val km = runCatching { KotlinClassMetadata.readLenient(metadata) }.getOrNull()) {
            is KotlinClassMetadata.Class -> decodeClass(km.kmClass, ctx)
            is KotlinClassMetadata.FileFacade -> decodePackage(km.kmPackage, ctx)
            is KotlinClassMetadata.MultiFileClassPart -> decodePackage(km.kmPackage, ctx)
            else -> null
        }
    }

    private fun decodeClass(km: KmClass, ctx: KotlinTypeContext?): Decoded {
        val classFqn = km.name.replace('/', '.')
        val owner = KotlinSymbol(classFqn.substringAfterLast('.'), SymbolKind.CLASS, origin = BINARY)
        val classTp = km.typeParameters.associate { it.id to it.name }
        val own = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()
        km.functions.forEach { f -> funcSymbol(f, ctx, owner, classTp).let { if (it.isExtension) ext += it else own += it } }
        km.properties.forEach { p -> propSymbol(p, ctx, owner, classTp).let { if (it.isExtension) ext += it else own += it } }
        km.constructors.forEach { c ->
            own += KotlinSymbol(
                name = classFqn.substringAfterLast('.'),
                kind = SymbolKind.CONSTRUCTOR,
                type = ctx?.let { KotlinType(classFqn, context = it) },
                owner = owner,
                origin = BINARY,
                signature = "(" + c.valueParameters.joinToString(", ") { vp -> "${vp.name}: ${typeText(vp.type, classTp)}" } + ")",
            )
        }
        return Decoded(classFqn, km.supertypes.mapNotNull { classifierFqn(it) }, km.typeParameters.map { it.name }, own, emptyList(), ext)
    }

    private fun decodePackage(km: KmPackage, ctx: KotlinTypeContext?): Decoded {
        val top = ArrayList<KotlinSymbol>()
        val ext = ArrayList<KotlinSymbol>()
        km.functions.forEach { f -> funcSymbol(f, ctx, null, emptyMap()).let { if (it.isExtension) ext += it else top += it } }
        km.properties.forEach { p -> propSymbol(p, ctx, null, emptyMap()).let { if (it.isExtension) ext += it else top += it } }
        return Decoded(null, emptyList(), emptyList(), emptyList(), top, ext)
    }

    private fun funcSymbol(f: KmFunction, ctx: KotlinTypeContext?, owner: KotlinSymbol?, classTp: Map<Int, String>): KotlinSymbol {
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
            paramTypes = f.valueParameters.map { typeRef(it.varargElementType ?: it.type, ctx, tp) },
            receiverTypeArgs = receiver?.arguments?.map { arg -> arg.type?.let { typeRef(it, ctx, tp) } ?: KotlinType("kotlin.Any", context = ctx) } ?: emptyList(),
            receiverTypeParam = recvParam,
        )
    }

    private fun propSymbol(p: KmProperty, ctx: KotlinTypeContext?, owner: KotlinSymbol?, classTp: Map<Int, String>): KotlinSymbol {
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
        )
    }

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
        // `T.() -> R` (apply/with/run blocks, DSL builders) carries @kotlin.ExtensionFunctionType on the type.
        val isExtFn = TypeRendering.isFunctionType(fqn) &&
            runCatching { t.annotations.any { it.className == "kotlin/ExtensionFunctionType" } }.getOrDefault(false)
        return KotlinType(fqn, args, nullable = t.isNullable, context = ctx, isExtensionFunctionType = isExtFn)
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
