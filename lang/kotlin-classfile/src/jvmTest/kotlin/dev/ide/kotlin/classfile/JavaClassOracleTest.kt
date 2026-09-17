package dev.ide.kotlin.classfile

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/**
 * The Java half of the decoder, diffed against ASM.
 *
 * A Kotlin library's members come from `@Metadata`; a Java one's come from the bytecode, and there are far
 * more of the latter on a real classpath than of the former. `android.jar` alone is around forty thousand
 * classes with no Kotlin metadata at all, and it is where the generics live: an index that cannot read a
 * generic signature cannot tell `list.get(0)` that it returns a `String`.
 *
 * So this compares, member for member and signature for signature, against the library it replaces. Wrong
 * answers here are not exceptions either: a mis-parsed signature yields a type that is merely the wrong one.
 */
class JavaClassOracleTest {

    /** Every jar on the test runtime classpath: a mixed Java and Kotlin corpus written by several compilers. */
    private fun classpathJars(): List<File> =
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.isFile && it.name.endsWith(".jar") }
            .sortedBy { it.name }

    /**
     * `android.jar`, when this machine has an SDK.
     *
     * Optional by necessity (CI has no Android SDK) but not optional in spirit: it is the single biggest
     * thing a real classpath holds, it is entirely Java, and it is the corpus the index was built for.
     */
    private fun androidJar(): File? {
        val home = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: "${System.getProperty("user.home")}/Library/Android/sdk"
        val platforms = File(home, "platforms")
        if (!platforms.isDirectory) return null
        return platforms.listFiles().orEmpty()
            .mapNotNull { File(it, "android.jar").takeIf(File::isFile) }
            .maxByOrNull { it.parentFile.name }
    }

    @Test
    fun theClassShapeMatchesAsmAcrossTheClasspath() {
        val jars = classpathJars()
        assertTrue(jars.size > 3, "expected a classpath of jars; found ${jars.size}")

        var classes = 0
        var members = 0
        for (jar in jars) classes += compareJar(jar) { members += it }
        assertTrue(classes > 2000, "expected a real corpus; compared $classes classes")
        println("java shape: $classes classes and $members members across ${jars.size} jars agree with ASM")
    }

    @Test
    fun theClassShapeMatchesAsmAcrossAndroidJar() {
        val jar = androidJar()
        if (jar == null) {
            println("no Android SDK on this machine; skipping android.jar")
            return
        }
        var members = 0
        val classes = compareJar(jar) { members += it }
        assertTrue(classes > 5000, "expected all of android.jar; compared $classes classes")
        println("android.jar (${jar.parentFile.name}): $classes classes and $members members agree with ASM")
    }

    /** Reads every class in [jar] with both readers and asserts they say the same thing. */
    private fun compareJar(jar: File, onMembers: (Int) -> Unit): Int {
        var classes = 0
        JarFile(jar).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .toList()
            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val ours = ClassFile.read(bytes)
                    ?: throw AssertionError("failed to read ${entry.name} in ${jar.name}")
                val theirs = AsmShape.of(bytes)
                val where = "${entry.name} in ${jar.name}"

                assertEquals(theirs.name, ours.thisClass, "class name of $where")
                assertEquals(theirs.access, ours.accessFlags, "access flags of $where")
                assertEquals(theirs.signature, ours.signature, "class signature of $where")
                assertEquals(theirs.superName, ours.superClass, "superclass of $where")
                assertEquals(theirs.interfaces, ours.interfaces, "interfaces of $where")
                assertEquals(theirs.fields, ours.fields.map(::render), "fields of $where")
                assertEquals(theirs.methods, ours.methods.map(::render), "methods of $where")
                assertEquals(theirs.innerClasses, ours.innerClasses.map(::render), "inner classes of $where")

                classes++
                onMembers(ours.fields.size + ours.methods.size)
            }
        }
        return classes
    }

    private fun render(member: ClassMember): String =
        "${member.access}|${member.name}|${member.descriptor}|${member.signature}|" +
            "${member.parameterNames}|${member.annotations.sorted()}"

    private fun render(reference: InnerClassRef): String =
        "${reference.access}|${reference.name}|${reference.outerName}|${reference.innerName}"

    @Test
    fun everyGenericSignatureParsesTheWayAsmReadsIt() {
        // A signature is where the wrong answer is most plausible: `Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;` is
        // an Entry whose OUTER carries the arguments, and flattening that to `Map$Entry` loses them without
        // failing. Every signature in the corpus is parsed and rendered by both readers and diffed.
        val jars = classpathJars() + listOfNotNull(androidJar())
        var classSignatures = 0
        var methodSignatures = 0
        var fieldSignatures = 0

        for (jar in jars) {
            JarFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val ours = ClassFile.read(bytes) ?: continue
                    val where = "${entry.name} in ${jar.name}"

                    ours.signature?.let { signature ->
                        val mine = assertNotNull(
                            JavaSignatures.parseClassSignature(signature),
                            "unparsed class signature `$signature` in $where",
                        )
                        assertEquals(
                            AsmSignature.ofClass(signature),
                            renderClass(mine),
                            "class signature `$signature` in $where",
                        )
                        classSignatures++
                    }

                    for (method in ours.methods) {
                        val signature = method.signature ?: continue
                        val mine = assertNotNull(
                            JavaSignatures.parseMethodSignature(signature),
                            "unparsed method signature `$signature` in $where",
                        )
                        assertEquals(
                            AsmSignature.ofMethod(signature),
                            renderMethod(mine),
                            "signature of ${method.name} `$signature` in $where",
                        )
                        methodSignatures++
                    }

                    for (field in ours.fields) {
                        val signature = field.signature ?: continue
                        val mine = assertNotNull(
                            JavaSignatures.parseFieldSignature(signature),
                            "unparsed field signature `$signature` in $where",
                        )
                        assertEquals(
                            AsmSignature.ofField(signature),
                            mine.render(),
                            "signature of ${field.name} `$signature` in $where",
                        )
                        fieldSignatures++
                    }
                }
            }
        }

        assertTrue(classSignatures > 200, "expected real generic classes; found $classSignatures")
        assertTrue(methodSignatures > 2000, "expected real generic methods; found $methodSignatures")
        println(
            "generic signatures: $classSignatures class, $methodSignatures method and " +
                "$fieldSignatures field signatures agree with ASM",
        )
    }

    @Test
    fun everyDescriptorParsesTheWayAsmReadsIt() {
        // The erased half. Cheap to get right and cheaper to get subtly wrong: `[[Ljava/lang/String;` and
        // `[Ljava/lang/String;` differ by one character and by one dimension.
        var compared = 0
        for (jar in classpathJars() + listOfNotNull(androidJar())) {
            JarFile(jar).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                    val ours = ClassFile.read(zip.getInputStream(entry).use { it.readBytes() }) ?: continue
                    for (method in ours.methods) {
                        val mine = assertNotNull(
                            JavaSignatures.parseMethodDescriptor(method.descriptor),
                            "unparsed descriptor `${method.descriptor}`",
                        )
                        val asm = Type.getMethodType(method.descriptor)
                        // ASM keeps the JVM's `$` between nested names; `render()` normalizes it to a dot,
                        // which is the whole point of having a display form. The `$` itself is not lost, and
                        // [nestedNamesKeepTheirBinarySpelling] is where that is asserted rather than assumed.
                        assertEquals(
                            asm.argumentTypes.map { it.className.replace('$', '.') },
                            mine.parameterTypes.map { it.render() },
                            "parameters of `${method.descriptor}`",
                        )
                        assertEquals(
                            asm.returnType.className.replace('$', '.'),
                            mine.returnType.render(),
                            "return of `${method.descriptor}`",
                        )
                        compared++
                    }
                    for (field in ours.fields) {
                        val mine = assertNotNull(JavaSignatures.parseTypeDescriptor(field.descriptor))
                        assertEquals(
                            Type.getType(field.descriptor).className.replace('$', '.'),
                            mine.render(),
                            "type of `${field.descriptor}`",
                        )
                        compared++
                    }
                }
            }
        }
        assertTrue(compared > 20000, "expected a real sample of descriptors; compared $compared")
        println("descriptors: $compared agree with ASM")
    }

    @Test
    fun nestedNamesKeepTheirBinarySpelling() {
        // `render()` normalizes `$` to `.` because the resolver compares against source-shaped names, and a
        // `$` in a binary name is indistinguishable from a `$` someone put in an identifier. That is a
        // display choice, not a loss: the name as parsed is still the JVM's own, which is what a classpath
        // lookup needs.
        val nested = assertNotNull(JavaSignatures.parseTypeDescriptor("Ljava/util/Map${'$'}Entry;")) as JavaType.Class
        assertEquals("java/util/Map${'$'}Entry", nested.name)
        assertEquals("java.util.Map.Entry", nested.render())
        assertEquals("java/util/Map${'$'}Entry", nested.binaryName())

        // The signature grammar spells the same type as an outer with a suffix, and then the OUTER carries
        // the type arguments. Flattening it would silently drop them.
        val generic = assertNotNull(
            JavaSignatures.parseFieldSignature("Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;"),
        ) as JavaType.Class
        assertEquals("Entry", generic.name)
        assertEquals("java.util.Map<K, V>.Entry<K, V>", generic.render())
        assertEquals("java/util/Map${'$'}Entry", generic.binaryName())
        assertEquals(listOf("K", "V"), generic.outer?.arguments?.map { it.render() })
    }

    private fun renderClass(signature: JavaClassSignature): String = buildString {
        append(signature.typeParameters.joinToString(", ", "<", ">") {
            "${it.name}: ${it.erasureBound().render()}"
        })
        append(" : ")
        append(listOfNotNull(signature.superclass).plus(signature.interfaces).joinToString(", ") { it.render() })
    }

    private fun renderMethod(signature: JavaMethodSignature): String = buildString {
        append(signature.typeParameters.joinToString(", ", "<", ">") {
            "${it.name}: ${it.erasureBound().render()}"
        })
        append(signature.parameterTypes.joinToString(", ", "(", ")") { it.render() })
        append(": ").append(signature.returnType.render())
    }

    /** ASM's reading of a class, flattened to values so a mismatch reads as a diff rather than a stack trace. */
    private class AsmShape(
        val access: Int,
        val name: String?,
        val signature: String?,
        val superName: String?,
        val interfaces: List<String>,
        val fields: List<String>,
        val methods: List<String>,
        val innerClasses: List<String>,
    ) {
        companion object {
            fun of(bytes: ByteArray): AsmShape {
                var access = 0
                var name: String? = null
                var signature: String? = null
                var superName: String? = null
                var interfaces: List<String> = emptyList()
                val fields = ArrayList<String>()
                val methods = ArrayList<String>()
                val inners = ArrayList<String>()

                ClassReader(bytes).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visit(
                            version: Int,
                            classAccess: Int,
                            className: String?,
                            classSignature: String?,
                            superClass: String?,
                            classInterfaces: Array<out String>?,
                        ) {
                            access = classAccess
                            name = className
                            signature = classSignature
                            superName = superClass
                            interfaces = classInterfaces?.toList().orEmpty()
                        }

                        override fun visitInnerClass(
                            inner: String,
                            outerName: String?,
                            innerName: String?,
                            innerAccess: Int,
                        ) {
                            inners.add("$innerAccess|$inner|$outerName|$innerName")
                        }

                        override fun visitField(
                            fieldAccess: Int,
                            fieldName: String,
                            descriptor: String,
                            fieldSignature: String?,
                            value: Any?,
                        ): FieldVisitor? {
                            val annotations = ArrayList<String>()
                            fields.add(
                                "$fieldAccess|$fieldName|$descriptor|$fieldSignature|[]|$annotations",
                            )
                            return object : FieldVisitor(Opcodes.ASM9) {
                                override fun visitAnnotation(
                                    annotationDescriptor: String,
                                    visible: Boolean,
                                ): AnnotationVisitor? {
                                    annotations.add(annotationDescriptor)
                                    annotations.sort()
                                    fields[fields.lastIndex] =
                                        "$fieldAccess|$fieldName|$descriptor|$fieldSignature|[]|$annotations"
                                    return null
                                }
                            }
                        }

                        override fun visitMethod(
                            methodAccess: Int,
                            methodName: String,
                            descriptor: String,
                            methodSignature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor {
                            val parameters = ArrayList<String>()
                            val annotations = ArrayList<String>()
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitParameter(parameterName: String?, parameterAccess: Int) {
                                    parameters.add(parameterName.orEmpty())
                                }

                                override fun visitAnnotation(
                                    annotationDescriptor: String,
                                    visible: Boolean,
                                ): AnnotationVisitor? {
                                    annotations.add(annotationDescriptor)
                                    return null
                                }

                                override fun visitEnd() {
                                    methods.add(
                                        "$methodAccess|$methodName|$descriptor|$methodSignature|" +
                                            "$parameters|${annotations.sorted()}",
                                    )
                                }
                            }
                        }
                    },
                    // NOT SKIP_DEBUG: ASM gates the MethodParameters attribute on it, and the real parameter
                    // names are the reason this is read at all. The same flags :lang-kotlin-index uses.
                    ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
                )
                return AsmShape(access, name, signature, superName, interfaces, fields, methods, inners)
            }
        }
    }

    /**
     * ASM's reading of a generic signature, rendered the way ours renders.
     *
     * `SignatureVisitor` is a push API with no tree behind it, so this rebuilds one. It is the part of the
     * test most likely to be wrong on its own, which is why it is kept to the same handful of shapes the
     * index consumes rather than made general.
     */
    private object AsmSignature {

        fun ofClass(signature: String): String {
            val visitor = ClassShape()
            SignatureReader(signature).accept(visitor)
            return "${visitor.formals()} : ${visitor.superTypes.joinToString(", ")}"
        }

        fun ofMethod(signature: String): String {
            val visitor = MethodShape()
            SignatureReader(signature).accept(visitor)
            return "${visitor.formals()}${visitor.parameters.joinToString(", ", "(", ")")}: ${visitor.returnType}"
        }

        fun ofField(signature: String): String {
            var result = ""
            SignatureReader(signature).acceptType(Collector { result = it })
            return result
        }

        /** Formal type parameters and their erasure bound, which is the leftmost bound of either kind. */
        private open class Formals : SignatureVisitor(Opcodes.ASM9) {
            private val names = ArrayList<String>()
            private val bounds = ArrayList<String>()
            private var boundTaken = false

            override fun visitFormalTypeParameter(name: String) {
                names.add(name)
                bounds.add("java.lang.Object")
                boundTaken = false
            }

            override fun visitClassBound(): SignatureVisitor = bound()
            override fun visitInterfaceBound(): SignatureVisitor = bound()

            private fun bound(): SignatureVisitor = Collector { type ->
                if (!boundTaken && bounds.isNotEmpty()) {
                    bounds[bounds.lastIndex] = type
                    boundTaken = true
                }
            }

            fun formals(): String = names.indices.joinToString(", ", "<", ">") { "${names[it]}: ${bounds[it]}" }
        }

        /**
         * `SignatureVisitor` is an abstract CLASS, so the formal-parameter half cannot be shared by
         * delegation and is shared by inheritance instead.
         */
        private class ClassShape : Formals() {
            val superTypes = ArrayList<String>()
            override fun visitSuperclass(): SignatureVisitor = Collector { superTypes.add(it) }
            override fun visitInterface(): SignatureVisitor = Collector { superTypes.add(it) }
        }

        private class MethodShape : Formals() {
            val parameters = ArrayList<String>()
            var returnType = ""
            override fun visitParameterType(): SignatureVisitor = Collector { parameters.add(it) }
            override fun visitReturnType(): SignatureVisitor = Collector { returnType = it }
            override fun visitExceptionType(): SignatureVisitor = Collector { }
        }

        /** Rebuilds one type from the push callbacks. */
        private class Collector(private val onDone: (String) -> Unit) : SignatureVisitor(Opcodes.ASM9) {
            private var arrayDepth = 0
            private val chain = StringBuilder()
            private var arguments = ArrayList<String>()
            private var simple: String? = null
            private var done = false

            override fun visitBaseType(descriptor: Char) {
                simple = JavaType.primitiveName(descriptor)
                finish()
            }

            override fun visitTypeVariable(name: String) {
                simple = name
                finish()
            }

            override fun visitArrayType(): SignatureVisitor {
                arrayDepth++
                return this
            }

            override fun visitClassType(name: String) {
                chain.append(name.replace('/', '.').replace('$', '.'))
            }

            override fun visitInnerClassType(name: String) {
                flushArguments()
                chain.append('.').append(name)
            }

            override fun visitTypeArgument() {
                arguments.add("*")
            }

            override fun visitTypeArgument(wildcard: Char): SignatureVisitor = Collector { type ->
                arguments.add(
                    when (wildcard) {
                        '+' -> "out $type"
                        '-' -> "in $type"
                        else -> type
                    },
                )
            }

            override fun visitEnd() {
                flushArguments()
                finish()
            }

            private fun flushArguments() {
                if (arguments.isNotEmpty()) {
                    chain.append(arguments.joinToString(", ", "<", ">"))
                    arguments = ArrayList()
                }
            }

            private fun finish() {
                if (done) return
                done = true
                onDone((simple ?: chain.toString()) + "[]".repeat(arrayDepth))
            }
        }
    }
}
