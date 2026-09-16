package dev.ide.kotlin.symbols

import dev.ide.kotlin.classfile.ClassFile
import dev.ide.lang.kotlin.symbols.JavaBytecode
import dev.ide.lang.kotlin.symbols.JavaShape as RealShape
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.TypeRef
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The question the two decoder modules exist to answer: does `:lang-kotlin-index` actually port?
 *
 * Agreeing with ASM about what is IN a class file is not the same claim as the consumer agreeing about what
 * it MEANS. The consumer is four hundred lines of rules: which members are hidden, how a JVM primitive maps
 * to a Kotlin classifier, what a `byte[][]` is called, when a parameter name is real, which order members
 * come in. Every one of those is a place a rewrite goes wrong silently, and no oracle at the ASM level can
 * see any of them.
 *
 * So both implementations are run over the same class files and every field of every symbol is compared.
 */
class JavaSymbolsPortTest {

    private fun classpathJars(): List<File> =
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") }
            .sortedBy { it.name }

    private fun androidJar(): File? {
        val home = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        return File(home, "platforms").listFiles().orEmpty()
            .mapNotNull { File(it, "android.jar").takeIf(File::isFile) }
            .maxByOrNull { it.parentFile.name }
    }

    @Test
    fun theWholeClasspathProducesTheSameSymbols() {
        val jars = classpathJars()
        assertTrue(jars.size > 3, "expected a classpath of jars; found ${jars.size}")
        var classes = 0
        var symbols = 0
        for (jar in jars) classes += compare(jar) { symbols += it }
        assertTrue(classes > 2000, "expected a real corpus; compared $classes classes")
        println("port: $classes classes and $symbols symbols across ${jars.size} jars are identical")
    }

    @Test
    fun androidJarProducesTheSameSymbols() {
        // The corpus this layer exists for. Entirely Java, entirely without Kotlin metadata, and the single
        // biggest thing on a real classpath.
        val jar = androidJar()
        if (jar == null) {
            println("no Android SDK on this machine; skipping android.jar")
            return
        }
        var symbols = 0
        val classes = compare(jar) { symbols += it }
        assertTrue(classes > 5000, "expected all of android.jar; compared $classes classes")
        println("port: android.jar (${jar.parentFile.name}) $classes classes and $symbols symbols are identical")
    }

    private fun compare(jar: File, onSymbols: (Int) -> Unit): Int {
        var classes = 0
        JarFile(jar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val theirs = JavaBytecode.read(bytes, null) ?: continue
                val ours = assertNotNull(JavaSymbols.read(bytes), "reading ${entry.name}")
                val where = "${entry.name} in ${jar.name}"

                assertEquals(theirs.typeParameters, ours.typeParameters, "type parameters of $where")
                assertEquals(
                    theirs.typeParameterBounds.map(::render),
                    ours.typeParameterBounds.map(::render),
                    "type parameter bounds of $where",
                )
                assertEquals(
                    theirs.superTypes.map(::render),
                    ours.superTypes.map(::render),
                    "supertypes of $where",
                )
                assertEquals(theirs.isInterface, ours.isInterface, "isInterface of $where")
                assertEquals(theirs.isAbstract, ours.isAbstract, "isAbstract of $where")
                assertEquals(theirs.isFinal, ours.isFinal, "isFinal of $where")
                assertEquals(
                    theirs.members.map(::render),
                    ours.members.map(::render),
                    "members of $where",
                )

                classes++
                onSymbols(ours.members.size)
            }
        }
        return classes
    }

    /**
     * Every field of a symbol, in one string.
     *
     * Rendering rather than comparing field by field so that a failure prints the whole symbol: a member
     * list that differs in one modifier is unreadable as two hundred-element lists of object identities.
     */
    private fun render(symbol: KotlinSymbol): String = render(
        kind = symbol.kind.name,
        name = symbol.name,
        type = symbol.type?.let(::render),
        modifiers = symbol.modifiers.map { it.name }.sorted(),
        signature = symbol.signature,
        typeParameters = symbol.typeParameters,
        typeParameterBounds = symbol.typeParameterBounds.map(::render),
        paramTypes = symbol.paramTypes.map { it?.let(::render) },
        paramNames = symbol.paramNames,
        declaringClassFqn = symbol.declaringClassFqn,
        isDeprecated = symbol.isDeprecated,
        varargParamIndex = symbol.varargParamIndex,
    )

    private fun render(symbol: JavaSymbol): String = render(
        kind = symbol.kind.name,
        name = symbol.name,
        type = symbol.type?.let(::render),
        modifiers = symbol.modifiers.map { it.name }.sorted(),
        signature = symbol.signature,
        typeParameters = symbol.typeParameters,
        typeParameterBounds = symbol.typeParameterBounds.map(::render),
        paramTypes = symbol.paramTypes.map { it?.let(::render) },
        paramNames = symbol.paramNames,
        declaringClassFqn = symbol.declaringClassFqn,
        isDeprecated = symbol.isDeprecated,
        varargParamIndex = symbol.varargParamIndex,
    )

    @Suppress("LongParameterList")
    private fun render(
        kind: String,
        name: String,
        type: String?,
        modifiers: List<String>,
        signature: String?,
        typeParameters: List<String>,
        typeParameterBounds: List<String>,
        paramTypes: List<String?>,
        paramNames: List<String>,
        declaringClassFqn: String?,
        isDeprecated: Boolean,
        varargParamIndex: Int,
    ): String = listOf(
        kind, name, type, modifiers, signature, typeParameters, typeParameterBounds,
        paramTypes, paramNames, declaringClassFqn, isDeprecated, varargParamIndex,
    ).joinToString(" | ")

    /** Theirs. `isTypeParameter` is part of it: a `T` that lost the flag renders the same and is not. */
    private fun render(type: TypeRef): String {
        val kotlinType = type as? KotlinType
        return render(
            type.qualifiedName,
            type.typeArguments.map(::render),
            kotlinType?.isTypeParameter == true,
            kotlinType?.projection.orEmpty(),
        )
    }

    private fun render(type: TypeName): String =
        render(type.qualifiedName, type.typeArguments.map(::render), type.isTypeParameter, type.projection)

    private fun render(
        qualifiedName: String,
        arguments: List<String>,
        isTypeParameter: Boolean,
        projection: String,
    ): String = buildString {
        if (projection == "*") {
            append("*<")
        } else {
            if (projection.isNotEmpty()) append(projection).append(' ')
            if (isTypeParameter) append('#')
            append(qualifiedName)
            append('<')
        }
        append(arguments.joinToString(", "))
        append('>')
    }

    @Test
    fun theTwoPathsDisagreeAboutNestedNames() {
        // Carried over from the original rather than fixed, and recorded here so it is a known fact instead
        // of a surprise. The ERASED path normalises the binary `$` to a dot, with a comment explaining that
        // an assignment check false-flagged a mismatch without it. The GENERIC-signature path does not, so
        // the same nested type has two spellings depending on whether the member that mentions it happened
        // to carry a signature attribute.
        //
        // Both implementations do this, which is why the corpus comparison above is green. Fixing it is a
        // change to :lang-kotlin-index, not to the port.
        val bytes = assertNotNull(
            javaClass.classLoader.getResourceAsStream("java/util/HashMap.class")?.readBytes()
                ?: findOnClasspath("java/util/HashMap.class"),
            "expected java.util.HashMap to be readable",
        )
        val shape = assertNotNull(JavaSymbols.read(bytes))
        val theirs = assertNotNull(JavaBytecode.read(bytes, null))

        val entrySpellings = (shape.members.mapNotNull { it.type?.qualifiedName } +
            shape.superTypes.map { it.qualifiedName })
            .filter { it.contains("Map") && (it.contains("Entry")) }
            .toSet()
        val theirSpellings = (theirs.members.mapNotNull { it.type?.qualifiedName } +
            theirs.superTypes.map { it.qualifiedName })
            .filter { it.contains("Map") && (it.contains("Entry")) }
            .toSet()
        assertEquals(theirSpellings, entrySpellings, "the port reproduces the original's spellings")
        println("nested-name spellings seen in java.util.HashMap: $entrySpellings")
    }

    @Test
    fun anInnerClassOfAGenericOuterMergesArguments() {
        // A second thing the port found and reproduced rather than fixed. The signature grammar writes a
        // non-static inner class of a generic outer as `LOuter<A;B;>.Inner<C;>;`, and the original's visitor
        // accumulates arguments across `visitClassType` and `visitInnerClassType` into ONE list, reporting
        // them all against the innermost name. So `Outer<A, B>.Inner<C>` becomes a single type called
        // `Outer.Inner` carrying three arguments, on a type declared with one.
        //
        // It is rare because it needs a generic outer AND a non-static inner, which is why it has survived.
        // Asserted here so that it is a recorded fact rather than a surprise, and so that the day
        // :lang-kotlin-index fixes it, this fails and says why.
        var examined = 0
        var example: String? = null
        for (jar in classpathJars()) {
            JarFile(jar).use { zip ->
                for (entry in zip.entries().asSequence()) {
                    if (!entry.name.endsWith(".class")) continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val classFile = ClassFile.read(bytes) ?: continue
                    // The suffix form, and only it: `>.` cannot appear in any other position.
                    if (classFile.methods.none { it.signature?.contains(">.") == true }) continue

                    val theirs = JavaBytecode.read(bytes, null) ?: continue
                    val ours = assertNotNull(JavaSymbols.read(bytes))
                    assertEquals(
                        theirs.members.map(::render),
                        ours.members.map(::render),
                        "members of ${entry.name}",
                    )
                    if (example == null) {
                        example = ours.members
                            .flatMap { it.paramTypes.filterNotNull() }
                            .firstOrNull { it.qualifiedName.count { c -> c == '.' } > 0 && it.typeArguments.size > 1 }
                            ?.render()
                    }
                    examined++
                }
            }
        }
        assertTrue(examined > 0, "expected at least one inner-class-of-a-generic-outer signature in the corpus")
        println("inner-of-generic-outer: $examined classes agree, e.g. $example")
    }

    private fun findOnClasspath(name: String): ByteArray? {
        for (jar in classpathJars()) {
            JarFile(jar).use { zip ->
                val entry = zip.getEntry(name)
                if (entry != null) return zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        return null
    }

    @Test
    fun aKotlinClassIsStillReadAsBytecode() {
        // The Java path does not care whether a class has Kotlin metadata: the index reads BOTH shapes for a
        // Kotlin class, the metadata for its Kotlin signatures and the bytecode for what the JVM actually
        // has. So the port has to agree on Kotlin classes too, not only on android.jar.
        val jar = classpathJars().firstOrNull { it.name.startsWith("kotlin-stdlib-") }
        if (jar == null) {
            println("kotlin-stdlib is not on the test classpath; skipping")
            return
        }
        var kotlinClasses = 0
        JarFile(jar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (ClassFile.read(bytes)?.isKotlin != true) continue
                val theirs = JavaBytecode.read(bytes, null) ?: continue
                val ours = assertNotNull(JavaSymbols.read(bytes))
                assertEquals(
                    theirs.members.map(::render),
                    ours.members.map(::render),
                    "members of ${entry.name}",
                )
                kotlinClasses++
            }
        }
        assertTrue(kotlinClasses > 500, "expected the stdlib's Kotlin classes; compared $kotlinClasses")
        println("port: $kotlinClasses Kotlin classes also read identically as plain bytecode")
    }
}
