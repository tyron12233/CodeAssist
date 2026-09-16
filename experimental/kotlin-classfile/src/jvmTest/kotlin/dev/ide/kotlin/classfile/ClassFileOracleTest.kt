package dev.ide.kotlin.classfile

import java.io.File
import kotlin.metadata.KmClass
import kotlin.metadata.KmPackage
import kotlin.metadata.jvm.KotlinClassMetadata
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
 * about real input or it is not, and the failure mode is not an exception — a wrong protobuf field number
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

    private fun KmClass.declarationNames(): List<String> =
        functions.map { it.name } + properties.map { it.name } + typeAliases.map { it.name }

    private fun KmPackage.declarationNames(): List<String> =
        functions.map { it.name } + properties.map { it.name } + typeAliases.map { it.name }
}
