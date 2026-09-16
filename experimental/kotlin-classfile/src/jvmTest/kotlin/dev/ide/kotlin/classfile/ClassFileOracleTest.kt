package dev.ide.kotlin.classfile

import java.io.File
import java.util.jar.JarFile
import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmPackage
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.isConst
import kotlin.metadata.isData
import kotlin.metadata.isExpect
import kotlin.metadata.isExternal
import kotlin.metadata.isFunInterface
import kotlin.metadata.isInfix
import kotlin.metadata.isInline
import kotlin.metadata.isInner
import kotlin.metadata.isLateinit
import kotlin.metadata.isNullable
import kotlin.metadata.isOperator
import kotlin.metadata.isSuspend
import kotlin.metadata.isTailrec
import kotlin.metadata.isValue
import kotlin.metadata.isVar
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.kind
import kotlin.metadata.modality
import kotlin.metadata.visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader

/**
 * The spike, and the thing that decides whether porting this is a week or a quarter: read real `.class` files
 * with no JVM library, and check the answers against the JVM libraries that do it today.
 *
 * Hand-written cases would prove nothing here. A decoder for someone else's binary format is either right
 * about real input or it is not, and the failure mode is not an exception: a wrong protobuf field number
 * reads a DIFFERENT field and returns something that looks plausible. So the oracle is ASM and
 * kotlin-metadata-jvm over this build's own output.
 *
 * Both are `jvmTest` dependencies only. They are precisely what this module exists to stop needing.
 */
class ClassFileOracleTest {

    private fun classFiles(limit: Int): List<File> {
        val root = File(System.getProperty("kotlinClassfile.repoRoot")!!)
        val candidates = listOf(
            // This build's own Kotlin output: real classes, current compiler, metadata in the modern
            // UTF-8 encoding.
            File(root, "experimental/kotlin-syntax/build/classes/kotlin/jvm/main"),
            File(root, "lang/lang-kotlin-index/build/classes/kotlin/main"),
            File(root, "platform/platform-core/build/classes/kotlin/main"),
        )
        return candidates.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "class" } }
            .sortedBy { it.path }
            .take(limit)
    }

    @Test
    fun theClassStructureMatchesAsm() {
        val files = classFiles(400)
        assertTrue(files.size > 50, "expected compiled output to read; found ${files.size}. Build first.")

        var compared = 0
        for (file in files) {
            val bytes = file.readBytes()
            val ours = ClassFile.read(bytes)
            assertNotNull(ours, "failed to read ${file.name}")

            val asm = ClassReader(bytes)
            assertEquals(asm.className, ours.thisClass, "class name in ${file.name}")
            assertEquals(asm.superName, ours.superClass, "superclass in ${file.name}")
            assertEquals(
                asm.interfaces.toList(),
                ours.interfaces,
                "interfaces in ${file.name}",
            )
            compared++
        }
        println("class structure: $compared files agree with ASM")
    }

    @Test
    fun theKotlinMetadataMatchesTheJvmLibrary() {
        val files = classFiles(400)
        var withMetadata = 0
        var declarationsCompared = 0

        for (file in files) {
            val bytes = file.readBytes()
            val ours = ClassFile.read(bytes) ?: continue
            val annotation = ours.metadata ?: continue

            // What the JVM library makes of the same annotation.
            val theirs = KotlinClassMetadata.readStrict(
                kotlin.Metadata(
                    kind = annotation.kind,
                    metadataVersion = annotation.metadataVersion,
                    data1 = annotation.data1,
                    data2 = annotation.data2,
                ),
            )
            val expected: List<String> = when (theirs) {
                is KotlinClassMetadata.Class -> theirs.kmClass.declarationNames()
                is KotlinClassMetadata.FileFacade -> theirs.kmPackage.declarationNames()
                is KotlinClassMetadata.MultiFileClassPart -> theirs.kmPackage.declarationNames()
                else -> continue
            }

            val decoded = KotlinMetadata.read(annotation)
            assertNotNull(decoded, "no metadata decoded for ${file.name}")
            assertEquals(
                expected.sorted(),
                decoded.declarations.map { it.name }.filter { it != "<init>" }.sorted(),
                "declarations in ${file.name}",
            )
            withMetadata++
            declarationsCompared += expected.size
        }

        assertTrue(withMetadata > 30, "expected Kotlin classes in the corpus; found $withMetadata")
        println("kotlin metadata: $withMetadata classes, $declarationsCompared declarations agree with kotlin-metadata-jvm")
    }

    @Test
    fun aClassNameIsReadFromTheMetadataToo() {
        // The name in the metadata is Kotlin's spelling, which is not always the JVM's.
        val file = classFiles(400).firstOrNull {
            ClassFile.read(it.readBytes())?.metadata?.kind == 1
        }
        assertNotNull(file, "expected at least one Kotlin class")
        val decoded = assertNotNull(KotlinMetadata.read(ClassFile.read(file.readBytes())!!.metadata!!))
        assertNotNull(decoded.name, "a class kind must carry its own name")
        assertTrue(decoded.name!!.isNotBlank())
    }

    @Test
    fun aJavaClassFileHasNoKotlinMetadata() {
        // The negative case matters: reporting metadata on a Java class would mean the annotation search is
        // matching on something other than the descriptor.
        val bytes = javaClass.classLoader.getResourceAsStream("java/lang/String.class")?.readBytes()
        if (bytes == null) {
            println("java.lang.String is not readable as a resource here; skipping")
            return
        }
        val read = assertNotNull(ClassFile.read(bytes))
        assertEquals("java/lang/String", read.thisClass)
        assertTrue(!read.isKotlin, "a Java class must not report Kotlin metadata")
    }

    @Test
    fun garbageIsRejectedRatherThanThrown() {
        // A classpath contains jars built by anything, and an index that throws on one file stops indexing.
        assertEquals(null, ClassFile.read(ByteArray(0)))
        assertEquals(null, ClassFile.read(byteArrayOf(1, 2, 3, 4)))
        assertEquals(null, ClassFile.read(ByteArray(200) { it.toByte() }))
    }

    @Test
    fun typesAndSignaturesMatchTheJvmLibrary() {
        // The part most likely to be subtly wrong. A name is one index; a type is a nested message with
        // several mutually exclusive classifier fields, and getting the wrong one back still renders as a
        // perfectly plausible type.
        val files = classFiles(400)
        var compared = 0
        var classes = 0

        for (file in files) {
            val annotation = ClassFile.read(file.readBytes())?.metadata ?: continue
            val theirs = KotlinClassMetadata.readStrict(
                kotlin.Metadata(
                    kind = annotation.kind,
                    metadataVersion = annotation.metadataVersion,
                    data1 = annotation.data1,
                    data2 = annotation.data2,
                ),
            )
            val expected: Map<String, String> = when (theirs) {
                is KotlinClassMetadata.Class -> theirs.kmClass.signatures()
                is KotlinClassMetadata.FileFacade -> theirs.kmPackage.signatures()
                is KotlinClassMetadata.MultiFileClassPart -> theirs.kmPackage.signatures()
                else -> continue
            }
            val decoded = KotlinMetadata.read(annotation) ?: continue
            val actual = decoded.declarations
                .filter { it.kind == KotlinDeclaration.Kind.FUNCTION }
                .associate { it.name to it.signature() }

            for ((name, signature) in expected) {
                val ours = actual[name] ?: continue
                assertEquals(signature, ours, "signature of `$name` in ${file.name}")
                compared++
            }
            classes++
        }

        assertTrue(compared > 200, "expected a real sample of signatures; compared $compared")
        println("types and signatures: $compared function signatures across $classes classes agree")
    }

    @Test
    fun theFlagsMatchTheJvmLibrary() {
        // Flags are a packed bit field whose layout exists only as a chain of offsets in the compiler, each
        // one a consequence of the widths before it. Every way of getting it wrong produces a valid-looking
        // answer: `private` for a `public` function, `abstract` for a `final` one. Only an oracle finds that.
        val files = classFiles(400)
        var classesCompared = 0
        var membersCompared = 0

        for (file in files) {
            val annotation = ClassFile.read(file.readBytes())?.metadata ?: continue
            val theirs = readWithTheJvmLibrary(annotation)
            val ours = KotlinMetadata.read(annotation) ?: continue

            when (theirs) {
                is KotlinClassMetadata.Class -> {
                    val km = theirs.kmClass
                    val where = "${km.name} in ${file.name}"
                    assertEquals(km.visibility.name, ours.visibility?.name, "class visibility of $where")
                    assertEquals(km.modality.name, ours.modality?.name, "class modality of $where")
                    assertEquals(km.kind.name, ours.classKind?.name, "class kind of $where")
                    assertEquals(km.isData, ours.isData, "isData of $where")
                    assertEquals(km.isInner, ours.isInner, "isInner of $where")
                    assertEquals(km.isValue, ours.isValue, "isValue of $where")
                    assertEquals(km.isFunInterface, ours.isFunInterface, "isFunInterface of $where")
                    assertEquals(km.isExpect, ours.isExpect, "isExpect of $where")
                    assertEquals(km.isExternal, ours.isExternal, "isExternal of $where")
                    classesCompared++
                    membersCompared += compareMembers(km.functions, km.properties, ours, file.name)
                }

                is KotlinClassMetadata.FileFacade ->
                    membersCompared += compareMembers(
                        theirs.kmPackage.functions,
                        theirs.kmPackage.properties,
                        ours,
                        file.name,
                    )

                else -> continue
            }
        }

        assertTrue(classesCompared > 30, "expected Kotlin classes in the corpus; found $classesCompared")
        assertTrue(membersCompared > 300, "expected a real sample of members; compared $membersCompared")
        println("flags: $classesCompared classes and $membersCompared members agree with kotlin-metadata-jvm")
    }

    /**
     * Members are compared position by position, not looked up by name: overloads share a name, and matching
     * on one would quietly compare a function against its own overload and pass.
     */
    private fun compareMembers(
        functions: List<KmFunction>,
        properties: List<KmProperty>,
        ours: KotlinClassInfo,
        fileName: String,
    ): Int {
        val ourFunctions = ours.declarations.filter { it.kind == KotlinDeclaration.Kind.FUNCTION }
        val ourProperties = ours.declarations.filter { it.kind == KotlinDeclaration.Kind.PROPERTY }
        assertEquals(functions.map { it.name }, ourFunctions.map { it.name }, "function order in $fileName")
        assertEquals(properties.map { it.name }, ourProperties.map { it.name }, "property order in $fileName")

        for ((theirs, mine) in functions.zip(ourFunctions)) {
            val where = "fun ${theirs.name} in $fileName"
            assertEquals(theirs.visibility.name, mine.visibility?.name, "visibility of $where")
            assertEquals(theirs.modality.name, mine.modality?.name, "modality of $where")
            assertEquals(theirs.kind.name, mine.memberKind?.name, "member kind of $where")
            assertEquals(theirs.isSuspend, mine.isSuspend, "isSuspend of $where")
            assertEquals(theirs.isInline, mine.isInline, "isInline of $where")
            assertEquals(theirs.isInfix, mine.isInfix, "isInfix of $where")
            assertEquals(theirs.isOperator, mine.isOperator, "isOperator of $where")
            assertEquals(theirs.isTailrec, mine.isTailrec, "isTailrec of $where")
            assertEquals(theirs.isExpect, mine.isExpect, "isExpect of $where")
            assertEquals(theirs.isExternal, mine.isExternal, "isExternal of $where")
        }

        for ((theirs, mine) in properties.zip(ourProperties)) {
            val where = "property ${theirs.name} in $fileName"
            assertEquals(theirs.visibility.name, mine.visibility?.name, "visibility of $where")
            assertEquals(theirs.modality.name, mine.modality?.name, "modality of $where")
            assertEquals(theirs.kind.name, mine.memberKind?.name, "member kind of $where")
            assertEquals(theirs.isVar, mine.isVar, "isVar of $where")
            assertEquals(theirs.isConst, mine.isConst, "isConst of $where")
            assertEquals(theirs.isLateinit, mine.isLateinit, "isLateinit of $where")
            assertEquals(theirs.isExpect, mine.isExpect, "isExpect of $where")
            assertEquals(theirs.isExternal, mine.isExternal, "isExternal of $where")
            // The JVM library has no opinion on hasGetter/hasSetter: it drops both on read, since a Kotlin
            // property always has a getter at the language level even when no JVM method exists for it (a
            // `private val` is read straight off the field). So these two bits are checked against what the
            // compiler actually writes instead. That still pins them, because the bits on either side are
            // checked against the oracle: isVar is bit 8 and isConst is bit 11, leaving exactly these two.
            assertTrue(mine.hasGetter, "hasGetter of $where")
            assertEquals(theirs.isVar, mine.hasSetter, "hasSetter of $where")
        }

        return functions.size + properties.size
    }

    @Test
    fun theJvmSignaturesMatchTheJvmLibrary() {
        // The half of the decoder that is NOT in the protobuf. Most members carry no signature at all,
        // because the compiler writes one only when it is not derivable from the Kotlin declaration; getting
        // this right is mostly getting the RECONSTRUCTION right, and a descriptor that is one argument
        // short (an extension receiver, say) names no method that exists.
        val files = classFiles(400)
        var compared = 0

        for (file in files) {
            val annotation = ClassFile.read(file.readBytes())?.metadata ?: continue
            val theirs = readWithTheJvmLibrary(annotation)
            val ours = KotlinMetadata.read(annotation) ?: continue

            val (theirFunctions, theirProperties, theirConstructors) = when (theirs) {
                is KotlinClassMetadata.Class ->
                    Triple(theirs.kmClass.functions, theirs.kmClass.properties, theirs.kmClass.constructors)

                is KotlinClassMetadata.FileFacade ->
                    Triple(theirs.kmPackage.functions, theirs.kmPackage.properties, emptyList<KmConstructor>())

                else -> continue
            }

            compared += compareSignatures(theirFunctions, theirProperties, theirConstructors, ours, file.name)
        }

        assertTrue(compared > 400, "expected a real sample of members; compared $compared")
        println("jvm signatures: $compared members agree")
    }

    /**
     * Compares the JVM name and descriptor of every member, position by position.
     *
     * A property is up to three JVM members and a function is one, and the ones that are ABSENT matter as
     * much as the ones that are present: reporting a backing field for a property that has none sends a
     * lookup to a field that does not exist. So a null on either side has to match a null on the other.
     */
    private fun compareSignatures(
        functions: List<KmFunction>,
        properties: List<KmProperty>,
        constructors: List<KmConstructor>,
        ours: KotlinClassInfo,
        fileName: String,
    ): Int {
        var compared = 0

        val ourFunctions = ours.declarations.filter { it.kind == KotlinDeclaration.Kind.FUNCTION }
        for ((theirs, mine) in functions.zip(ourFunctions)) {
            assertEquals(
                theirs.signature?.toString(),
                mine.jvmSignature?.toString(),
                "JVM signature of fun ${theirs.name} in $fileName",
            )
            compared++
        }

        val ourConstructors = ours.declarations.filter { it.kind == KotlinDeclaration.Kind.CONSTRUCTOR }
        for ((theirs, mine) in constructors.zip(ourConstructors)) {
            assertEquals(
                theirs.signature?.toString(),
                mine.jvmSignature?.toString(),
                "JVM signature of a constructor in $fileName",
            )
            compared++
        }

        val ourProperties = ours.declarations.filter { it.kind == KotlinDeclaration.Kind.PROPERTY }
        for ((theirs, mine) in properties.zip(ourProperties)) {
            val where = "property ${theirs.name} in $fileName"
            assertEquals(
                theirs.fieldSignature?.let { "${it.name}:${it.descriptor}" },
                mine.propertySignatures?.field?.let { "${it.name}:${it.descriptor}" },
                "backing field of $where",
            )
            assertEquals(
                theirs.getterSignature?.toString(),
                mine.propertySignatures?.getter?.toString(),
                "getter of $where",
            )
            assertEquals(
                theirs.setterSignature?.toString(),
                mine.propertySignatures?.setter?.toString(),
                "setter of $where",
            )
            compared += 3
        }

        return compared
    }

    @Test
    fun theStandardLibraryDecodesTheSameWayTheJvmLibraryDoes() {
        // Our own build output is a soft corpus: one compiler, one code style, no legacy anything. The
        // kotlin-stdlib jar is the corpus this module actually has to survive, and it is adversarial by
        // construction: value classes, multi-file facades, mangled names, `Map.Entry` and the rest of the
        // predefined string table, inline and infix and operator functions by the hundred, and metadata
        // written across a decade of compiler versions.
        val jar = kotlinStdlibJar()
        if (jar == null) {
            println("kotlin-stdlib is not on the test classpath; skipping")
            return
        }

        var structures = 0
        var classes = 0
        var members = 0
        var signatures = 0
        var refusedByTheLibrary = 0

        JarFile(jar).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .sortedBy { it.name }
                .toList()
            assertTrue(entries.size > 500, "expected a whole stdlib; ${jar.name} holds ${entries.size} classes")

            for (entry in entries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val ours = assertNotNull(ClassFile.read(bytes), "failed to read ${entry.name}")

                val asm = ClassReader(bytes)
                assertEquals(asm.className, ours.thisClass, "class name in ${entry.name}")
                assertEquals(asm.superName, ours.superClass, "superclass in ${entry.name}")
                assertEquals(asm.interfaces.toList(), ours.interfaces, "interfaces in ${entry.name}")
                structures++

                val annotation = ours.metadata ?: continue
                // The library refuses metadata versions it does not support outright. Anything it will not
                // read is not something this can be checked against, so it is counted and skipped rather
                // than papered over.
                val theirs = runCatching { readWithTheJvmLibrary(annotation) }.getOrNull()
                if (theirs == null) {
                    refusedByTheLibrary++
                    continue
                }
                // Throwing is a failure in its own right: a classpath index that dies on one entry stops
                // indexing, so the contract is an answer or null, never an exception.
                val mine = try {
                    KotlinMetadata.read(annotation)
                } catch (e: Throwable) {
                    throw AssertionError("decoding ${entry.name} threw", e)
                } ?: continue

                val (theirFunctions, theirProperties, theirConstructors) = when (theirs) {
                    is KotlinClassMetadata.Class -> {
                        val km = theirs.kmClass
                        val where = "${km.name} in ${entry.name}"
                        assertEquals(km.name.replace('/', '.'), mine.name, "class name of $where")
                        assertEquals(km.visibility.name, mine.visibility?.name, "class visibility of $where")
                        assertEquals(km.modality.name, mine.modality?.name, "class modality of $where")
                        assertEquals(km.kind.name, mine.classKind?.name, "class kind of $where")
                        assertEquals(km.isData, mine.isData, "isData of $where")
                        assertEquals(km.isInner, mine.isInner, "isInner of $where")
                        assertEquals(km.isValue, mine.isValue, "isValue of $where")
                        assertEquals(km.isFunInterface, mine.isFunInterface, "isFunInterface of $where")
                        classes++
                        Triple(km.functions, km.properties, km.constructors)
                    }

                    is KotlinClassMetadata.FileFacade ->
                        Triple(theirs.kmPackage.functions, theirs.kmPackage.properties, emptyList<KmConstructor>())

                    is KotlinClassMetadata.MultiFileClassPart ->
                        Triple(theirs.kmPackage.functions, theirs.kmPackage.properties, emptyList<KmConstructor>())

                    else -> continue
                }

                members += compareMembers(theirFunctions, theirProperties, mine, entry.name)
                signatures += compareSignatures(theirFunctions, theirProperties, theirConstructors, mine, entry.name)
            }
        }

        assertTrue(classes > 500, "expected most of the stdlib to be Kotlin classes; found $classes")
        println(
            "${jar.name}: $structures class files agree with ASM; " +
                "$classes classes, $members members and $signatures signatures agree with kotlin-metadata-jvm " +
                "($refusedByTheLibrary the library would not read)",
        )
    }

    @Test
    fun aMultiFileFacadeNamesItsPartsInsteadOfHoldingMembers() {
        // The case that crashed the decoder before the corpus got wide enough to contain one. A facade is
        // the only kind whose `d1` is not protobuf, and following it to its parts is the only way the
        // members of a `@JvmMultifileClass` file are reachable at all.
        val jar = kotlinStdlibJar()
        if (jar == null) {
            println("kotlin-stdlib is not on the test classpath; skipping")
            return
        }

        JarFile(jar).use { zip ->
            val facades = zip.entries().asSequence()
                .filter { it.name.endsWith(".class") }
                .mapNotNull { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    ClassFile.read(bytes)?.metadata?.takeIf { it.kind == 4 }?.let { entry.name to it }
                }
                .toList()
            assertTrue(facades.isNotEmpty(), "expected a @JvmMultifileClass in the stdlib")

            var partsFollowed = 0
            for ((name, annotation) in facades) {
                // A facade holds no declarations of its own, and saying otherwise would have an index
                // report the members twice: once here and once on the part that really has them.
                assertEquals(null, KotlinMetadata.read(annotation), "a facade has no declarations ($name)")

                val parts = assertNotNull(KotlinMetadata.readMultiFileParts(annotation), "parts of $name")
                assertTrue(parts.isNotEmpty(), "a facade names at least one part ($name)")

                for (part in parts) {
                    val entry = assertNotNull(zip.getEntry("$part.class"), "part $part named by $name")
                    val partAnnotation = assertNotNull(
                        ClassFile.read(zip.getInputStream(entry).use { it.readBytes() })?.metadata,
                        "metadata of part $part",
                    )
                    assertEquals(5, partAnnotation.kind, "$part is a multi-file part")
                    val decoded = assertNotNull(KotlinMetadata.read(partAnnotation), "declarations of $part")
                    assertTrue(decoded.declarations.isNotEmpty(), "$part holds the members $name does not")
                    partsFollowed++
                }
            }
            println("multi-file classes: ${facades.size} facades, $partsFollowed parts followed")
        }
    }

    private fun kotlinStdlibJar(): File? =
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .map(::File)
            .firstOrNull { it.name.startsWith("kotlin-stdlib-") && it.name.endsWith(".jar") }

    private fun readWithTheJvmLibrary(annotation: KotlinMetadataAnnotation): KotlinClassMetadata =
        KotlinClassMetadata.readStrict(
            kotlin.Metadata(
                kind = annotation.kind,
                metadataVersion = annotation.metadataVersion,
                data1 = annotation.data1,
                data2 = annotation.data2,
            ),
        )

    /** The JVM library's view, rendered the way `KotlinDeclaration.signature()` renders ours. */
    private fun KmClass.signatures(): Map<String, String> {
        val classScope = typeParameters.associate { it.id to it.name }
        return functions.associate { it.name to it.render(classScope) }
    }

    private fun KmPackage.signatures(): Map<String, String> =
        functions.associate { it.name to it.render(emptyMap()) }

    private fun kotlin.metadata.KmFunction.render(outer: Map<Int, String> = emptyMap()): String {
        // Their classifier names a type parameter by ID; ours renders the NAME, which is what a completion
        // list shows. Map one to the other here rather than weakening the comparison to ignore both.
        val scope = outer + typeParameters.associate { it.id to it.name }
        return buildString {
            receiverParameterType?.let { append(it.render(scope)).append('.') }
            append(name)
            append(valueParameters.joinToString(", ", "(", ")") { "${it.name}: ${it.type?.render(scope) ?: "?"}" })
            append(": ").append(returnType.render(scope))
        }
    }

    private fun KmType.render(scope: Map<Int, String>): String = buildString {
        append(
            when (val c = classifier) {
                is KmClassifier.Class -> c.name.replace('/', '.')
                is KmClassifier.TypeAlias -> c.name.replace('/', '.')
                is KmClassifier.TypeParameter -> scope[c.id] ?: ("T#" + c.id)
            },
        )
        if (arguments.isNotEmpty()) {
            append(arguments.joinToString(", ", "<", ">") { it.type?.render(scope) ?: "*" })
        }
        if (isNullable) append('?')
    }

    private fun KmClass.declarationNames(): List<String> =
        functions.map { it.name } + properties.map { it.name } + typeAliases.map { it.name }

    private fun KmPackage.declarationNames(): List<String> =
        functions.map { it.name } + properties.map { it.name } + typeAliases.map { it.name }
}
