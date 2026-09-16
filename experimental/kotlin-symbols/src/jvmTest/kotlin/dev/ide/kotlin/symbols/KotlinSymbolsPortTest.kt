package dev.ide.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.lang.kotlin.symbols.KotlinMetadata as RealKotlinMetadata
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.KotlinTypeContext
import dev.ide.lang.resolve.Symbol as RealSymbol
import dev.ide.lang.resolve.TypeRef
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin half of the port, diffed against the real `KotlinMetadata`.
 *
 * Everything bytecode ERASES lives here: extension functions, properties that are properties, nullability,
 * default arguments, use-site projections, and the suspend function type that is stored in one shape and
 * has to be read back in another. There is more room for a silent difference in this layer than anywhere
 * else in the stack, which is why every field of every symbol is compared rather than a summary.
 */
class KotlinSymbolsPortTest {

    /**
     * The resolution context the real implementation wants.
     *
     * It is a callback into the symbol service, used to walk supertypes and enumerate members lazily.
     * Nothing decoded here needs it, but the real code will not construct a constructor's or an enum
     * entry's type without one, so an empty one is passed rather than comparing against a null it would
     * only have produced because the test did not supply it.
     */
    private object EmptyContext : KotlinTypeContext {
        override fun membersOf(
            typeFqn: String,
            typeArgs: List<TypeRef>,
            accessibleFrom: RealSymbol?,
        ): List<RealSymbol> = emptyList()

        override fun supertypesOf(typeFqn: String): List<TypeRef> = emptyList()
    }

    private fun classpathJars(): List<File> =
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") }
            .sortedBy { it.name }

    @Test
    fun theStandardLibraryDecodesToTheSameSymbols() {
        val jar = classpathJars().firstOrNull { it.name.startsWith("kotlin-stdlib-") }
        assertNotNull(jar, "kotlin-stdlib must be on the test classpath")

        var units = 0
        var symbols = 0
        JarFile(jar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val theirs = RealKotlinMetadata.decode(bytes, EmptyContext) ?: continue
                val ours = assertNotNull(KotlinSymbols.decode(bytes), "decoding ${entry.name}")
                compare(theirs, ours, entry.name)
                units++
                symbols += ours.ownMembers.size + ours.topLevel.size + ours.extensions.size
            }
        }
        assertTrue(units > 800, "expected the whole stdlib; decoded $units units")
        assertTrue(symbols > 8000, "expected real symbols; decoded $symbols")
        println("kotlin port: $units units and $symbols symbols of kotlin-stdlib are identical")
    }

    @Test
    fun everyKotlinClassOnTheClasspathDecodesToTheSameSymbols() {
        // A wider and messier corpus than the stdlib: several compilers, coroutines, kotlin-metadata-jvm
        // itself, and whatever else the test runtime drags in.
        var units = 0
        var symbols = 0
        for (jar in classpathJars()) {
            JarFile(jar).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (ClassFile.read(bytes)?.isKotlin != true) continue
                    val theirs = RealKotlinMetadata.decode(bytes, EmptyContext) ?: continue
                    val ours = assertNotNull(KotlinSymbols.decode(bytes), "decoding ${entry.name}")
                    compare(theirs, ours, "${entry.name} in ${jar.name}")
                    units++
                    symbols += ours.ownMembers.size + ours.topLevel.size + ours.extensions.size
                }
            }
        }
        assertTrue(units > 1000, "expected a real corpus; decoded $units units")
        println("kotlin port: $units units and $symbols symbols across the classpath are identical")
    }

    private fun compare(theirs: RealKotlinMetadata.Decoded, ours: KotlinSymbols.Decoded, where: String) {
        assertEquals(theirs.classFqn, ours.classFqn, "class FQN of $where")
        assertEquals(
            theirs.supertypes.map(::render),
            ours.supertypes.map(::render),
            "supertypes of $where",
        )
        assertEquals(theirs.typeParameters, ours.typeParameters, "type parameters of $where")
        assertEquals(
            theirs.typeParameterVariances,
            ours.typeParameterVariances,
            "type parameter variances of $where",
        )
        assertEquals(theirs.facadeClassFqn, ours.facadeClassFqn, "facade of $where")
        assertEquals(theirs.companionObjectName, ours.companionObjectName, "companion of $where")
        assertEquals(theirs.isObject, ours.isObject, "isObject of $where")
        assertEquals(theirs.isInterface, ours.isInterface, "isInterface of $where")
        assertEquals(theirs.isAbstractClass, ours.isAbstractClass, "isAbstractClass of $where")
        assertEquals(theirs.isFinalClass, ours.isFinalClass, "isFinalClass of $where")
        assertEquals(theirs.sealedSubclasses, ours.sealedSubclasses, "sealed subclasses of $where")
        assertEquals(
            theirs.typeAliases.map { "${it.name}=${it.expandedFqn}" },
            ours.typeAliases.map { "${it.name}=${it.expandedFqn}" },
            "type aliases of $where",
        )
        assertEquals(
            theirs.ownMembers.map(::render),
            ours.ownMembers.map(::render),
            "own members of $where",
        )
        assertEquals(theirs.topLevel.map(::render), ours.topLevel.map(::render), "top level of $where")
        assertEquals(
            theirs.extensions.map(::render),
            ours.extensions.map(::render),
            "extensions of $where",
        )
    }

    private fun render(symbol: KotlinSymbol): String = render(
        kind = symbol.kind.name,
        name = symbol.name,
        type = symbol.type?.let(::render),
        owner = symbol.owner?.name,
        modifiers = symbol.modifiers.map { it.name }.sorted(),
        signature = symbol.signature,
        typeParameters = symbol.typeParameters,
        typeParamBoundNames = symbol.typeParamBoundNames,
        paramTypes = symbol.paramTypes.map { it?.let(::render) },
        paramNames = symbol.paramNames,
        paramHasDefault = symbol.paramHasDefault,
        receiverTypeFqn = symbol.receiverTypeFqn,
        receiverTypeArgs = symbol.receiverTypeArgs.map(::render),
        receiverTypeParam = symbol.receiverTypeParam,
        declaringClassFqn = symbol.declaringClassFqn,
        flags = listOf(
            symbol.isInternal, symbol.isComposable, symbol.isInline,
            symbol.isInfix, symbol.isSuspend,
        ),
        varargParamIndex = symbol.varargParamIndex,
    )

    private fun render(symbol: Symbol): String = render(
        kind = symbol.kind.name,
        name = symbol.name,
        type = symbol.type?.let(::render),
        owner = symbol.owner?.name,
        modifiers = symbol.modifiers.map { it.name }.sorted(),
        signature = symbol.signature,
        typeParameters = symbol.typeParameters,
        typeParamBoundNames = symbol.typeParamBoundNames,
        paramTypes = symbol.paramTypes.map { it?.let(::render) },
        paramNames = symbol.paramNames,
        paramHasDefault = symbol.paramHasDefault,
        receiverTypeFqn = symbol.receiverTypeFqn,
        receiverTypeArgs = symbol.receiverTypeArgs.map(::render),
        receiverTypeParam = symbol.receiverTypeParam,
        declaringClassFqn = symbol.declaringClassFqn,
        flags = listOf(
            symbol.isInternal, symbol.isComposable, symbol.isInline,
            symbol.isInfix, symbol.isSuspend,
        ),
        varargParamIndex = symbol.varargParamIndex,
    )

    @Suppress("LongParameterList")
    private fun render(
        kind: String,
        name: String,
        type: String?,
        owner: String?,
        modifiers: List<String>,
        signature: String?,
        typeParameters: List<String>,
        typeParamBoundNames: List<String?>,
        paramTypes: List<String?>,
        paramNames: List<String>,
        paramHasDefault: List<Boolean>,
        receiverTypeFqn: String?,
        receiverTypeArgs: List<String>,
        receiverTypeParam: String?,
        declaringClassFqn: String?,
        flags: List<Boolean>,
        varargParamIndex: Int,
    ): String = listOf(
        kind, name, type, owner, modifiers, signature, typeParameters, typeParamBoundNames,
        paramTypes, paramNames, paramHasDefault, receiverTypeFqn, receiverTypeArgs, receiverTypeParam,
        declaringClassFqn, flags, varargParamIndex,
    ).joinToString(" | ")

    private fun render(type: TypeRef): String {
        val kotlinType = type as? KotlinType
        return render(
            type.qualifiedName,
            type.typeArguments.map(::render),
            kotlinType?.isTypeParameter == true,
            kotlinType?.projection.orEmpty(),
            kotlinType?.nullable == true,
            kotlinType?.isExtensionFunctionType == true,
            kotlinType?.isComposable == true,
        )
    }

    private fun render(type: TypeName): String = render(
        type.qualifiedName,
        type.typeArguments.map(::render),
        type.isTypeParameter,
        type.projection,
        type.nullable,
        type.isExtensionFunctionType,
        type.isComposable,
    )

    @Suppress("LongParameterList")
    private fun render(
        qualifiedName: String,
        arguments: List<String>,
        isTypeParameter: Boolean,
        projection: String,
        nullable: Boolean,
        isExtensionFunctionType: Boolean,
        isComposable: Boolean,
    ): String = buildString {
        if (projection.isNotEmpty()) append(projection).append(' ')
        if (isTypeParameter) append('#')
        if (isExtensionFunctionType) append('@')
        if (isComposable) append('&')
        append(qualifiedName)
        append(arguments.joinToString(", ", "<", ">"))
        if (nullable) append('?')
    }
}
