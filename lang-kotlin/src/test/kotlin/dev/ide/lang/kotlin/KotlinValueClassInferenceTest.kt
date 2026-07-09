package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinCallableIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reproduction for the reported `Modifier.offset` issues (a value class = `@JvmInline value class Dp(Float)`),
 * driven through the REAL persistent index (the device path, which diverges from the in-memory scan path):
 *  - completion should offer the `FakeModifier.fakeOffset` extension (the `Modifier.offset` shape);
 *  - `10.fakeDp` must infer as `FakeDp`, NOT the underlying `Float` — so `val d: FakeDp = 10.fakeDp` and
 *    `fakeOffset(x = 10.fakeDp)` must not false-flag a `kt.typeMismatch`.
 */
class KotlinValueClassInferenceTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // --- direct index-path probes (KotlinSymbolService over the real index) ---

    @Test
    fun offsetExtensionIsIndexedWithValueClassParamType() {
        val offsets = service.extensionsFor("dev.ide.fakecompose.FakeModifier", emptyList(), "fakeOffset", exactName = true)
            .filter { it.name == "fakeOffset" }
        assertTrue(offsets.isNotEmpty(), "the fakeOffset extension must be in the index")
        val valueClassOverload = offsets.firstOrNull { o ->
            (o.paramTypes.firstOrNull() as? KotlinType)?.qualifiedName == "dev.ide.fakecompose.FakeDp"
        }
        assertNotNull(valueClassOverload, "an overload with a FakeDp (value-class) param must be indexed; got ${offsets.map { it.paramTypes }}")
    }

    @Test
    fun intDpExtensionPropertyReturnsValueClassNotFloat() {
        val dp = service.membersNamed("kotlin.Int", emptyList(), "fakeDp").firstOrNull { it.isExtension }
        assertNotNull(dp, "Int.fakeDp extension property must resolve from the index")
        assertEquals("dev.ide.fakecompose.FakeDp", (dp.type as? KotlinType)?.qualifiedName,
            "Int.fakeDp must be typed FakeDp (the value class), not its underlying Float; got ${dp.type}")
    }

    // --- analyzer (completion + diagnostics) through the index ---

    @Test
    fun valueClassExtensionPropertyDoesNotFalseFlagFloat() {
        val diags = diagnose(
            "A.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { val d: FakeDp = 10.fakeDp }",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "10.fakeDp is a FakeDp, not the Float it wraps; got $diags",
        )
    }

    @Test
    fun valueClassArgumentDoesNotFalseFlag() {
        val diags = diagnose(
            "B.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { FakeModifier.fakeOffset(x = 10.fakeDp) }",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "fakeOffset(x = 10.fakeDp) fits (FakeDp arg); got $diags",
        )
    }

    @Test
    fun offsetExtensionShowsInCompletion() = runBlocking {
        val result = analyzer.completeAtCaret(
            srcDir, "D.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { FakeModifier.fakeOff| }",
        )
        assertTrue(
            result.items.any { it.label.startsWith("fakeOffset") },
            "fakeOffset must be offered on FakeModifier.; got ${result.items.map { it.label }}",
        )
    }

    // --- `number * Dp` operator inference (the reported `2 * 4.dp` / `2f * 4.dp` → "Int/Float but Dp" bug) ---

    @Test
    fun floatTimesValueClassInfersValueClass() {
        // `2f * 10.fakeDp` is `Float.times(FakeDp): FakeDp` — the result is a FakeDp, NOT the left operand's Float.
        val diags = diagnose(
            "V1.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { val d: FakeDp = 2f * 10.fakeDp }",
        )
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "2f * 10.fakeDp is a FakeDp; got $diags")
    }

    @Test
    fun intTimesValueClassInfersValueClass() {
        val diags = diagnose(
            "V2.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { val d: FakeDp = 2 * 10.fakeDp }",
        )
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "2 * 10.fakeDp is a FakeDp; got $diags")
    }

    @Test
    fun numberTimesValueClassAsArgumentDoesNotFalseFlag() {
        // The exact reported shape: `Modifier.offset(x = 2 * 4.dp)`.
        val diags = diagnose(
            "V3.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { FakeModifier.fakeOffset(x = 2 * 10.fakeDp) }",
        )
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "fakeOffset(x = 2 * 10.fakeDp) fits; got $diags")
    }

    @Test
    fun numberTimesValueClassProductIsValueClassNotNull() = runBlocking {
        // Prove the product infers as FakeDp specifically (not merely null): its `.value` member resolves.
        val result = analyzer.completeAtCaret(
            srcDir, "V4.kt",
            "package demo\nimport dev.ide.fakecompose.*\nfun f() { val d = 2f * 10.fakeDp; d.val| }",
        )
        assertTrue(
            result.items.any { it.label.startsWith("value") },
            "(2f * 10.fakeDp).value should resolve, proving the product is a FakeDp; got ${result.items.map { it.label }}",
        )
    }

    @Test
    fun primitiveArithmeticStillPromotes() {
        // Guard: plain primitive×primitive must keep Kotlin's numeric promotion (the model carries no return type
        // for the built-in operators, so `arithmeticOperatorReturn` yields null and promotion runs). `2 * 3` stays
        // Int — valid against Int, and (proving it isn't null/some value class) flagged against String.
        assertTrue(diagnose("P1.kt", "package demo\nfun f() { val x: Int = 2 * 3 }").none { it.code == "kt.typeMismatch" }, "2*3 is Int")
        assertTrue(diagnose("P2.kt", "package demo\nfun f() { val s: String = 2 * 3 }").any { it.code == "kt.typeMismatch" }, "2*3 is Int, not String")
    }

    companion object {
        val srcDir: Path = tempProject(emptyMap())

        private val CLASSES = listOf(
            "dev/ide/fakecompose/FakeDp.class",
            "dev/ide/fakecompose/FakeDpKt.class",
            "dev/ide/fakecompose/FakeDensity.class",
            "dev/ide/fakecompose/FakeModifier.class",
            "dev/ide/fakecompose/FakeModifier\$Companion.class",
        )

        private fun fakeJar(): Path {
            val jar = Files.createTempFile("fake-dp", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                zos.putNextEntry(ZipEntry("META-INF/fakedp.kotlin_module")); zos.closeEntry()
                for (name in CLASSES) {
                    val bytes = KotlinValueClassInferenceTest::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                        ?: error("missing test class resource: $name")
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
            }
            return jar
        }

        private val jar = fakeJar()
        private val index = IndexServiceImpl(
            listOf(KotlinTypeShapeIndex, KotlinCallableIndex),
            cacheRoot = Files.createTempDirectory("idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = listOf(jar))) } }

        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(jar), index = index)

        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), jar)))
            .also { it.indexService = index }
    }
}
