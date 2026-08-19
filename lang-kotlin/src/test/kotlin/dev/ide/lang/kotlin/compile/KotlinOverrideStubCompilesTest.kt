package dev.ide.lang.kotlin.compile

import dev.ide.lang.kotlin.DiskFile
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.SnippetDoc
import dev.ide.lang.kotlin.fakeContext
import dev.ide.lang.kotlin.parse
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The end of the "Implement members" contract: what the quick-fix writes must COMPILE. Asserted against the
 * real K2 compiler rather than by string-matching, because every way this went wrong was a signature part the
 * stub silently dropped — and each is a compile error the user only meets after generating the code:
 *  - `suspend` → "Non-suspend function 'load' cannot override suspend function" (the reported bug);
 *  - the `<T>` list → `T` is an unresolved reference;
 *  - a type-parameter BOUND → the signature differs, so the stub overrides nothing and the class is still abstract;
 *  - `vararg` → same, the stub takes `Int` where the supertype takes `vararg Int`.
 *
 * [dev.ide.lang.kotlin.KotlinOverrideStubSignatureTest] pins the exact header text; this pins that it builds.
 */
class KotlinOverrideStubCompilesTest {

    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    @Test
    fun theGeneratedStubsCompile() {
        withTempDir("kt-override-stub") { dir ->
            val srcRoot = dir.resolve("src")
            val out = dir.resolve("out")
            val api = srcRoot.resolve("util/Api.kt")
            Files.createDirectories(api.parent)
            Files.writeString(
                api,
                """
                package util

                interface Repo {
                    suspend fun load(id: String): String
                    suspend fun flush()
                    fun plain(): Int
                    val name: String
                    fun <T> transform(t: T): T
                    fun <T : Number> clamp(t: T): T
                    fun sum(vararg xs: Int): Int
                    fun pack(head: String, vararg parts: String): String
                    fun mix(m: Map<String, Int>, f: (Int, Int) -> Unit)
                }
                """.trimIndent() + "\n",
            )
            val use = srcRoot.resolve("demo/Use.kt")
            Files.createDirectories(use.parent)
            val before = "package demo\n\nimport util.Repo\n\nclass R : Repo\n"
            Files.writeString(use, before)

            // Run the real analyzer over the incomplete class, then take the quick-fix it offers.
            val analyzer = KotlinSourceAnalyzer(fakeContext(srcRoot, listOf(stdlib)))
            val doc = SnippetDoc(before, DiskFile(use))
            val diagnostics = runBlocking {
                analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
            }
            val missing = diagnostics.firstOrNull { it.code == "kt.abstractNotImplemented" }
            assertNotNull(missing, "expected the missing-abstract diagnostic; got ${diagnostics.map { it.code }}")
            val fix = analyzer.implementMembersFix(doc.file, missing.range.start)
            assertNotNull(fix, "expected an Implement-members fix at the class name")

            val after = fix.edits.sortedByDescending { it.offset }.fold(before) { text, e ->
                text.substring(0, e.offset) + e.newText + text.substring(e.offset + e.oldLength)
            }
            Files.writeString(use, after)

            val result = IncrementalKotlinCompiler().compile(listOf(api, use), emptyList(), listOf(stdlib), out)
            assertTrue(
                result.success,
                "the generated stubs must compile; got\n$after\n---\n${result.messages.joinToString("\n")}",
            )
        }
    }
}
