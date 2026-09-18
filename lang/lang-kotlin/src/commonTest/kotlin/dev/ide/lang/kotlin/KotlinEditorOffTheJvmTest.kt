package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.lang.completion.CompletionRequest
import dev.ide.lang.completion.CompletionTrigger
import dev.ide.lang.completion.complete
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.kotlin.completion.KotlinCompletion
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.BuiltinsReader
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin editor, on whatever platform this runs on.
 *
 * Every layer under this has its own tests, and passing them separately is not this claim. The parser is
 * checked against the Kotlin compiler's own PSI over 774 files, the class-file and `@Metadata` decoders
 * against ASM and kotlin-metadata-jvm, the index against its own segments, the builtins decode against the
 * compiler's protobuf reader — each in isolation, most of them only on the JVM. What none of them says is
 * whether 25,000 lines of parse, symbol table, resolution, inference and completion actually RUN when
 * `java.*` is not there: whether the caches behave, the recursion guards hold, and a real source tree on a
 * real filesystem produces a real completion list.
 *
 * Compiling for a target proves none of that. So this is deliberately end to end and deliberately boring:
 * two source files on disk, one caret, and the answers an editor would show.
 */
class KotlinEditorOffTheJvmTest {

    private val dir = scratchPath("editor-${nowSuffix()}")

    @AfterTest
    fun cleanUp() {
        deleteTree(dir)
    }

    // ---- the source path: a real tree, walked, modelled, resolved, completed ----------------------------

    private fun projectWithTwoFiles(): KotlinSymbolService {
        writeSourceFile(
            dir, "Greeter.kt",
            """
            package demo

            class Greeter(val name: String) {
                fun greet(): String = "hi ${'$'}name"
                fun times(n: Int): Int = n * 2
                private fun hidden() = 1
            }
            """.trimIndent(),
        )
        writeSourceFile(
            dir, "Use.kt",
            """
            package demo

            fun run(): String {
                val g = Greeter("world")
                return g.greet()
            }
            """.trimIndent(),
        )
        return KotlinSymbolService(
            sourceRoots = listOf<VirtualFile>(DiskSourceFile(dir)),
            classpathJars = emptyList(),
        )
    }

    @Test
    fun aTypeDeclaredInAnotherFileResolvesAndOffersItsMembers() {
        projectWithTwoFiles().use { service ->
            val members = service.membersOf("demo.Greeter", emptyList(), null).map { it.name }
            assertTrue("greet" in members, "a cross-file class's members must resolve; got $members")
            assertTrue("times" in members, "got $members")
            assertTrue("name" in members, "a constructor `val` is a property; got $members")
        }
    }

    @Test
    fun completingAfterADotOffersTheReceiversMembers() = runTest {
        projectWithTwoFiles().use { service ->
            val text = """
                package demo

                fun other(): String {
                    val g = Greeter("world")
                    return g.
                }
            """.trimIndent()
            val offset = text.indexOf("g.") + 2
            val items = KotlinCompletion(service)
                .complete(
                    CompletionRequest(
                        Snippet(text, DiskSourceFile("$dir/Other.kt")),
                        offset,
                        CompletionTrigger.TypedChar('.'),
                    ),
                    KotlinLanguage.ID,
                )
                // A member's label carries its signature (`greet()`); the name is what this is about.
                .items.map { it.label.substringBefore('(') }

            assertTrue("greet" in items, "member completion off a cross-file type; got ${items.take(20)}")
            assertTrue("times" in items, "got ${items.take(20)}")
            assertTrue("hidden" !in items, "a private member is not offered from another file; got $items")
        }
    }

    @Test
    fun theParserProducesATreeWithTheDeclarationsInIt() {
        val kt = assertNotNull(KotlinParserHost.parse("A.kt", "package p\nclass A { fun f() = 1 }\n"))
        assertEquals("p", kt.packageFqName.asString())
        assertEquals(listOf("A"), kt.declarations.filterIsInstance<KtNamedDeclaration>().map { it.name })
    }

    // ---- the builtins path: the types no class file describes -------------------------------------------

    /**
     * `kotlin/annotation/annotation.kotlin_builtins`, verbatim out of `kotlin-stdlib` 2.4.0 (1,022 bytes).
     *
     * A real fragment, not a synthesized one: the point is that the format as the Kotlin compiler actually
     * writes it decodes here. Small, and it carries the two shapes that were hardest to get right — enum
     * ENTRIES (which are neither functions nor properties) and members whose types live in the type table.
     */
    private val annotationFragment: String =
        "AAAAAwAAAAEAAAAAAAAABwqHAwoGa290bGluCgphbm5vdGF0aW9uChNBbm5vdGF0aW9uUmV0ZW50aW9uCgRFbnVtCgZTT1VS" +
            "Q0UKBkJJTkFSWQoHUlVOVElNRQoQQW5ub3RhdGlvblRhcmdldAoFQ0xBU1MKEEFOTk9UQVRJT05fQ0xBU1MKDlRZUEVfUEFS" +
            "QU1FVEVSCghQUk9QRVJUWQoFRklFTEQKDkxPQ0FMX1ZBUklBQkxFCg9WQUxVRV9QQVJBTUVURVIKC0NPTlNUUlVDVE9SCghG" +
            "VU5DVElPTgoPUFJPUEVSVFlfR0VUVEVSCg9QUk9QRVJUWV9TRVRURVIKBFRZUEUKCkVYUFJFU1NJT04KBEZJTEUKCVRZUEVB" +
            "TElBUwoLU2luY2VLb3RsaW4KB3ZlcnNpb24KAzEuMQoQTXVzdEJlRG9jdW1lbnRlZAoKQW5ub3RhdGlvbgoGVGFyZ2V0Cg5h" +
            "bGxvd2VkVGFyZ2V0cwoKUmVwZWF0YWJsZQoJUmV0ZW50aW9uCgV2YWx1ZQoFQXJyYXkSWgoCEAAKBAgAEAEKBggBEAIYAAoG" +
            "CAAQAxgACgYIARAHGAAKBggAEBcYAAoGCAEQGhgACgYIABAbGAAKBggBEBwYAAoGCAEQHhgACgYIARAfGAAKBggAECEYABos" +
            "8gEmCgIwAgoGEgIYADADCgIwBAoGEgIYAjADCgIwBwoIEgQIARgCMAu4CQEiKAiGgQISAQEYAkICCEJqAggEagIIBWoCCAby" +
            "AQwKAjACCgYSAhgAMAMicwiGgQISAQMYBEICCEJqAggIagIICWoCCApqAggLagIIDGoCCA1qAggOagIID2oCCBBqAggRagII" +
            "EmoCCBNqAggUagIIFWoRCBayCQwIBRIICBgSBAgIKBnyARgKAjACCgYSAhgAMAMKAjAECgYSAhgCMAMiQAiHAhIBBBgGQgII" +
            "RvIBHAoCMAIKBhICGAAwAwoCMAQKBhICGAIwAwoCMAeyCRIICBIOCB0SCggMSgYICjAEOAkiQAiHAhIBBBgJQgIIRvIBHAoC" +
            "MAIKBhICGAAwAwoCMAQKBhICGAIwAwoCMAeyCRIICBIOCB0SCggMSgYICjAEOAkiUgiHAhIBBBgKQgoIRhIGCAIQICgAUggQ" +
            "IEgAWIaECPIBHAoCMAIKBhICGAAwAwoCMAQKBhICGAIwAwoCMAeyCRIICBIOCB0SCggMSgYICjAEOAkiYQiHAhIBBBgIQgoI" +
            "RhIGEB0oBTACUggQHUgFWIaECPIBJgoCMAIKBhICGAAwAwoCMAQKBhICGAIwAwoCMAcKCBIECAEYAjALsgkSCAgSDggdEgoI" +
            "DEoGCAowBDgJsgkCCAY="

    @Test
    @OptIn(ExperimentalEncodingApi::class)
    fun builtinTypesDecodeFromTheFragmentTheCompilerWrote() {
        val shapes = BuiltinsReader.shapesFrom(Base64.decode(annotationFragment))
        val target = assertNotNull(
            shapes["kotlin.annotation.AnnotationTarget"],
            "the builtins fragment declares AnnotationTarget; got ${shapes.keys}",
        )
        val entries = target.members.filter { it.kind.name == "ENUM_CONSTANT" }.map { it.name }
        assertTrue("CLASS" in entries, "an enum declared only in `.kotlin_builtins` keeps its entries; got $entries")
        assertTrue("FUNCTION" in entries, "got $entries")
        assertTrue("PROPERTY_GETTER" in entries, "got $entries")

        val retention = assertNotNull(shapes["kotlin.annotation.AnnotationRetention"])
        assertEquals(
            listOf("SOURCE", "BINARY", "RUNTIME"),
            retention.members.filter { it.kind.name == "ENUM_CONSTANT" }.map { it.name },
            "entries come back in declaration order",
        )
    }
}

/** An in-memory [DocumentSnapshot] over a snippet, standing in for an editor buffer. */
private class Snippet(private val content: String, override val file: VirtualFile) : DocumentSnapshot {
    override val version: Long = 1
    override val text: CharSequence get() = content
    override fun length(): Int = content.length
}
