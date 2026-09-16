package dev.ide.kotlin.classfile

/**
 * A type as JVM descriptors and generic signatures spell it.
 *
 * Two grammars describe the same types and a class file carries both: the erased DESCRIPTOR is mandatory,
 * the generic SIGNATURE is an optional attribute present only when generics are involved. An index needs
 * both, because the signature is what makes `list.stream()` on a `List<String>` know it is a
 * `Stream<String>`, and the descriptor is what is there when no generics exist.
 */
sealed class JavaType {

    /** `I`, `V`, `Z`: one of the nine single-letter types, `void` included. */
    class Primitive(val descriptor: Char) : JavaType()

    /**
     * A class, possibly generic, possibly nested inside another generic class.
     *
     * [outer] is not the enclosing package but the enclosing TYPE, and only the signature grammar records
     * it: `Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;` is an `Entry` whose outer `Map` carries its own arguments.
     * Flattening that to `java.util.Map$Entry` loses the outer's arguments, which is how a `Map.Entry<K, V>`
     * comes back as a raw `Entry`.
     */
    class Class(
        val name: String,
        val arguments: List<JavaTypeArgument> = emptyList(),
        val outer: Class? = null,
    ) : JavaType()

    /** `TE;`: a reference to a type parameter declared on the class or the method. */
    class Variable(val name: String) : JavaType()

    class Array(val element: JavaType) : JavaType()

    /**
     * The type as Java prints it: `java.util.List<java.lang.String>`, `E`, `int[]`.
     *
     * Nested names join with a dot rather than the `$` the JVM uses, matching how a Kotlin index spells
     * them: `$` in a binary name is not distinguishable from a `$` someone put in an identifier, and the
     * resolver compares against source-shaped names.
     */
    fun render(): String = when (this) {
        is Primitive -> primitiveName(descriptor)
        is Variable -> name
        is Array -> element.render() + "[]"
        is Class -> buildString {
            if (outer != null) append(outer.render()).append('.').append(name)
            else append(name.replace('/', '.').replace('$', '.'))
            if (arguments.isNotEmpty()) append(arguments.joinToString(", ", "<", ">") { it.render() })
        }
    }

    /**
     * The type this erases to, which is what the descriptor would have said.
     *
     * A type variable erases to its leftmost bound, and the bound is not knowable from the type itself, so
     * the caller supplies the scope it was declared in. An unknown variable erases to `java.lang.Object`,
     * which is what the JVM does too.
     */
    fun erasure(bounds: Map<String, JavaType> = emptyMap()): JavaType = when (this) {
        is Primitive -> this
        is Variable -> bounds[name]?.erasure(bounds) ?: Class("java/lang/Object")
        is Array -> Array(element.erasure(bounds))
        is Class -> Class(if (outer != null) binaryName() else name)
    }

    /** The JVM's own spelling, with `$` between nested names: `java/util/Map$Entry`. */
    fun binaryName(): String = when (this) {
        is Class -> if (outer == null) name else "${outer.binaryName()}$$name"
        else -> render()
    }

    override fun toString(): String = render()

    companion object {
        fun primitiveName(descriptor: Char): String = when (descriptor) {
            'B' -> "byte"
            'C' -> "char"
            'D' -> "double"
            'F' -> "float"
            'I' -> "int"
            'J' -> "long"
            'S' -> "short"
            'Z' -> "boolean"
            'V' -> "void"
            else -> descriptor.toString()
        }
    }
}

/**
 * One argument in a `<...>` list.
 *
 * [wildcard] is `'='` for a plain argument, `'+'` for `? extends`, `'-'` for `? super` and `'*'` for an
 * unbounded `?`, which is the same alphabet ASM's `SignatureVisitor` uses. A `'*'` carries no type at all,
 * which is why [type] is nullable rather than filled with `Object`.
 */
class JavaTypeArgument(val type: JavaType?, val wildcard: Char = '=') {
    fun render(): String = when (wildcard) {
        '*' -> "*"
        '+' -> "out ${type?.render()}"
        '-' -> "in ${type?.render()}"
        else -> type?.render() ?: "*"
    }

    override fun toString(): String = render()
}

/**
 * A formal type parameter: `E`, `T extends Comparable<T>`.
 *
 * The class bound may be absent (`T::Ljava/lang/Comparable;` bounds only by an interface), which is why the
 * erasure target is the first bound of EITHER kind rather than [classBound].
 */
class JavaTypeParameter(
    val name: String,
    val classBound: JavaType?,
    val interfaceBounds: List<JavaType>,
) {
    /** What a use of this parameter erases to: the leftmost bound, or `Object` when there is none. */
    fun erasureBound(): JavaType =
        classBound ?: interfaceBounds.firstOrNull() ?: JavaType.Class("java/lang/Object")
}

class JavaClassSignature(
    val typeParameters: List<JavaTypeParameter>,
    val superclass: JavaType?,
    val interfaces: List<JavaType>,
)

class JavaMethodSignature(
    val typeParameters: List<JavaTypeParameter>,
    val parameterTypes: List<JavaType>,
    val returnType: JavaType,
    val exceptions: List<JavaType>,
)

/**
 * Descriptors and generic signatures, parsed.
 *
 * Both grammars are small, unambiguous and fully specified (JVMS 4.3 and 4.7.9.1), and both are
 * single-pass: there is no lookahead anywhere and no construct whose meaning depends on what follows. That
 * is what makes replacing ASM's `Type` and `SignatureReader` a parser rather than a project.
 *
 * Every entry point returns null rather than throwing on input it cannot parse. A classpath holds jars
 * written by every tool that ever emitted bytecode, and one malformed attribute must cost that member, not
 * the index.
 */
object JavaSignatures {

    /** `(Ljava/lang/String;I)V`, erased. */
    fun parseMethodDescriptor(descriptor: String): JavaMethodSignature? = runCatching {
        val cursor = Cursor(descriptor)
        cursor.expect('(')
        val parameters = ArrayList<JavaType>()
        while (cursor.peek() != ')') parameters.add(cursor.readType())
        cursor.expect(')')
        val returnType = cursor.readType()
        JavaMethodSignature(emptyList(), parameters, returnType, emptyList())
    }.getOrNull()

    /** `Ljava/lang/String;`, `[[I`, `I`. */
    fun parseTypeDescriptor(descriptor: String): JavaType? =
        runCatching { Cursor(descriptor).readType() }.getOrNull()

    /** `<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/List<TT;>;` */
    fun parseClassSignature(signature: String): JavaClassSignature? = runCatching {
        val cursor = Cursor(signature)
        val typeParameters = cursor.readTypeParameters()
        val superclass = cursor.readType()
        val interfaces = ArrayList<JavaType>()
        while (!cursor.atEnd) interfaces.add(cursor.readType())
        JavaClassSignature(typeParameters, superclass, interfaces)
    }.getOrNull()

    /** `<T:Ljava/lang/Object;>(TT;)Ljava/util/List<TT;>;^Ljava/io/IOException;` */
    fun parseMethodSignature(signature: String): JavaMethodSignature? = runCatching {
        val cursor = Cursor(signature)
        val typeParameters = cursor.readTypeParameters()
        cursor.expect('(')
        val parameters = ArrayList<JavaType>()
        while (cursor.peek() != ')') parameters.add(cursor.readType())
        cursor.expect(')')
        val returnType = cursor.readType()
        val exceptions = ArrayList<JavaType>()
        while (!cursor.atEnd) {
            cursor.expect('^')
            exceptions.add(cursor.readType())
        }
        JavaMethodSignature(typeParameters, parameters, returnType, exceptions)
    }.getOrNull()

    /** A field's generic signature, which is a single type: `Ljava/util/List<TE;>;`, `TE;`. */
    fun parseFieldSignature(signature: String): JavaType? =
        runCatching { Cursor(signature).readType() }.getOrNull()

    /**
     * The one cursor both grammars share.
     *
     * The descriptor grammar is a strict subset of the signature grammar: everything a descriptor can say, a
     * signature can say the same way. So they are parsed by one reader, and a descriptor simply never
     * reaches the `T`, `<` and `.` branches.
     */
    private class Cursor(private val text: String) {
        private var at = 0

        val atEnd: Boolean get() = at >= text.length

        fun peek(): Char = text[at]

        fun expect(char: Char) {
            check(text[at] == char) { "expected `$char` at $at in `$text`" }
            at++
        }

        fun readTypeParameters(): List<JavaTypeParameter> {
            if (atEnd || peek() != '<') return emptyList()
            at++
            val parameters = ArrayList<JavaTypeParameter>()
            while (peek() != '>') {
                val name = readIdentifier()
                expect(':')
                // The class bound is written as an EMPTY slot when there is none, so a signature bounded
                // only by interfaces reads `T::Ljava/lang/Comparable;`. Treating the empty slot as a parse
                // error rejects a signature javac emits every day.
                val classBound = if (peek() == ':') null else readType()
                val interfaceBounds = ArrayList<JavaType>()
                while (peek() == ':') {
                    at++
                    interfaceBounds.add(readType())
                }
                parameters.add(JavaTypeParameter(name, classBound, interfaceBounds))
            }
            expect('>')
            return parameters
        }

        fun readType(): JavaType = when (val char = text[at]) {
            'L' -> readClassType()
            'T' -> {
                at++
                val name = readIdentifier()
                expect(';')
                JavaType.Variable(name)
            }

            '[' -> {
                at++
                JavaType.Array(readType())
            }

            else -> {
                at++
                JavaType.Primitive(char)
            }
        }

        private fun readClassType(): JavaType {
            expect('L')
            var name = readClassName()
            var arguments = readTypeArguments()
            var type = JavaType.Class(name, arguments)
            // `.Inner` suffixes. Each one is a class nested in the PREVIOUS one, and it may carry its own
            // arguments, so the chain is built outward-in rather than flattened into one name.
            while (peek() == '.') {
                at++
                name = readIdentifier()
                arguments = readTypeArguments()
                type = JavaType.Class(name, arguments, outer = type)
            }
            expect(';')
            return type
        }

        private fun readTypeArguments(): List<JavaTypeArgument> {
            if (peek() != '<') return emptyList()
            at++
            val arguments = ArrayList<JavaTypeArgument>()
            while (peek() != '>') {
                arguments.add(
                    when (val char = peek()) {
                        '*' -> {
                            at++
                            JavaTypeArgument(null, '*')
                        }

                        '+', '-' -> {
                            at++
                            JavaTypeArgument(readType(), char)
                        }

                        else -> JavaTypeArgument(readType(), '=')
                    },
                )
            }
            expect('>')
            return arguments
        }

        /** A class name runs to whichever of `<`, `.` or `;` comes first; `/` and `$` are part of it. */
        private fun readClassName(): String {
            val start = at
            while (text[at] != '<' && text[at] != '.' && text[at] != ';') at++
            return text.substring(start, at)
        }

        private fun readIdentifier(): String {
            val start = at
            while (at < text.length && text[at] !in ":;<.>") at++
            return text.substring(start, at)
        }
    }
}
