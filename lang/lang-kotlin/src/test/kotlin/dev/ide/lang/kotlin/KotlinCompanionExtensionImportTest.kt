package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An extension declared as a MEMBER of a companion object, brought into scope by importing it through that
 * companion, then called on a plain receiver. The canonical OkHttp/Retrofit idiom:
 *
 * ```
 * import okhttp3.MediaType.Companion.toMediaType
 * json.asConverterFactory("application/json".toMediaType())
 * ```
 *
 * The member-extension seam only surfaced these when the companion was an IMPLICIT receiver (declared in an
 * enclosing class, or a Compose-style `Modifier.Companion`), and the import path deliberately skipped
 * extensions, so a companion extension reached the way OkHttp intends resolved nowhere and the call was flagged
 * `unresolved reference`. Covered on the BINARY path (the real case: a jar's `@kotlin.Metadata`) and on the
 * project-source path.
 */
class KotlinCompanionExtensionImportTest {

    private fun unresolved(srcDir: Path, libJars: List<Path>, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val diags: List<Diagnostic> = runBlocking {
            analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
        }
        return diags.filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun binaryCompanionExtensionImportedThroughTheCompanionResolves() {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val u = unresolved(
            srcDir, listOf(fakeMediaTypeJar(), stdlibJarPath()),
            """
            package demo
            import dev.ide.fakecompose.FakeMediaType.Companion.fakeToMediaType

            fun f() {
                val m = "application/json".fakeToMediaType()
            }
            """.trimIndent(),
        )
        assertTrue(
            u.none { it.contains("fakeToMediaType") },
            "a companion member extension imported through its companion must resolve; got $u",
        )
    }

    /** The same call WITHOUT the import must still be flagged: the import is what brings it into scope. */
    @Test
    fun theSameCallWithoutTheImportIsStillFlagged() {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val u = unresolved(
            srcDir, listOf(fakeMediaTypeJar(), stdlibJarPath()),
            """
            package demo

            fun f() {
                val m = "application/json".fakeToMediaType()
            }
            """.trimIndent(),
        )
        assertTrue(
            u.any { it.contains("fakeToMediaType") },
            "without the import the extension is out of scope and must be reported; got $u",
        )
    }

    /** Kotlin also permits importing a companion member through the ENCLOSING class name. */
    @Test
    fun importingThroughTheEnclosingClassAlsoResolves() {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val u = unresolved(
            srcDir, listOf(fakeMediaTypeJar(), stdlibJarPath()),
            """
            package demo
            import dev.ide.fakecompose.FakeMediaType.fakeToMediaTypeOrNull

            fun f() {
                val m = "application/json".fakeToMediaTypeOrNull()
            }
            """.trimIndent(),
        )
        assertTrue(
            u.none { it.contains("fakeToMediaTypeOrNull") },
            "a companion member extension imported through the enclosing class must resolve; got $u",
        )
    }

    @Test
    fun sourceCompanionExtensionImportedThroughTheCompanionResolves() {
        val srcDir = tempProject(
            mapOf(
                "Media.kt" to """
                package media
                class Media(val text: String) {
                    companion object {
                        fun String.toMedia(): Media = Media(this)
                    }
                }
                """.trimIndent(),
            ),
        )
        val u = unresolved(
            srcDir, listOf(stdlibJarPath()),
            """
            package demo
            import media.Media.Companion.toMedia

            fun f() {
                val m = "text/plain".toMedia()
            }
            """.trimIndent(),
        )
        assertTrue(u.none { it.contains("toMedia") }, "the source path must resolve it too; got $u")
    }

    /** Resolution is only half of it: the import must also make the extension COMPLETE off the receiver. */
    @Test
    fun theImportedCompanionExtensionCompletesOnTheReceiver() {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(fakeMediaTypeJar(), stdlibJarPath())))
        val items = runBlocking {
            analyzer.completeAtCaret(
                srcDir, "Use.kt",
                "package demo\n" +
                    "import dev.ide.fakecompose.FakeMediaType.Companion.fakeToMediaType\n" +
                    "fun f() { \"application/json\".fakeToM| }",
            )
        }.items.mapNotNull { it.symbol?.name }
        assertTrue("fakeToMediaType" in items, "the imported companion extension should complete; got $items")
    }

    /** A regular class's member extension is NOT importable, so it must not leak onto a receiver. */
    @Test
    fun aPlainClassMemberExtensionDoesNotLeakThroughAnImport() {
        val srcDir = tempProject(
            mapOf(
                "Holder.kt" to """
                package holder
                class Holder {
                    fun String.secret(): String = this
                }
                """.trimIndent(),
            ),
        )
        val u = unresolved(
            srcDir, listOf(stdlibJarPath()),
            """
            package demo
            import holder.Holder.secret

            fun f() {
                val s = "x".secret()
            }
            """.trimIndent(),
        )
        assertTrue(
            u.any { it.contains("secret") },
            "a non-singleton container's member extension is not importable and must stay unresolved; got $u",
        )
    }

    /** Stage the compiled fixture into a Kotlin-looking jar the symbol service will scan. */
    private fun fakeMediaTypeJar(): Path {
        val jar = Files.createTempFile("fake-mediatype", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakemediatype.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeMediaType.class")
            add("dev/ide/fakecompose/FakeMediaType\$Companion.class")
        }
        return jar
    }
}
