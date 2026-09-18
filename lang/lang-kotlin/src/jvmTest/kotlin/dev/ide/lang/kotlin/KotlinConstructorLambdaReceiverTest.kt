package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A lambda ARGUMENT OF A CONSTRUCTOR must be typed by the parameter it fills, receiver included.
 *
 * Reported against the plugin UI API's `EditorPainter(paint: DrawScope.(EditorPaintContext) -> Unit)`: every
 * `DrawScope` member called in the paint block (`drawRoundRect(…)`) was flagged "Unresolved reference" while
 * the same code compiled. The cause is that the callee resolution behind lambda typing resolves FUNCTIONS,
 * and a constructor is not one — so the expected function type was never found and the block had no implicit
 * receiver. The equivalent call through a top-level function had always worked, which is why the Compose
 * content-lambda cases never showed it.
 */
class KotlinConstructorLambdaReceiverTest {

    private fun unresolved(srcDir: Path, code: String, jars: List<Path> = listOf(stdlibJarPath())): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, jars))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
        }.filter { it.code == "kt.unresolved" }.map { it.message }
    }

    // --- the reported shape, through a @Metadata binary exactly as the plugin API arrives ---

    @Test
    fun aBinaryConstructorsLambdaKeepsItsReceiver() {
        val u = unresolved(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            import dev.ide.fakecompose.FakePainter
            import dev.ide.fakecompose.FakePaintContext
            import dev.ide.fakecompose.PaintColor

            val painter = FakePainter(
                id = "color-picker",
                paint = { ctx: FakePaintContext -> drawRoundRect(PaintColor(0xFF0000L), ctx.lineHeight) },
            )
            """.trimIndent(),
            listOf(fakePainterJar(), stdlibJarPath()),
        )
        assertTrue(u.isEmpty(), "the paint block's receiver members must resolve; got $u")
    }

    @Test
    fun anUnknownMemberInThatLambdaIsStillFlagged() {
        // The control: establishing the receiver must not blanket-silence the check inside the block.
        val u = unresolved(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            import dev.ide.fakecompose.FakePainter
            import dev.ide.fakecompose.FakePaintContext

            val painter = FakePainter(
                id = "color-picker",
                paint = { ctx: FakePaintContext -> drawNothingLikeThis(1) },
            )
            """.trimIndent(),
            listOf(fakePainterJar(), stdlibJarPath()),
        )
        assertTrue(u.any { it.contains("drawNothingLikeThis") }, "a real typo must still be flagged; got $u")
    }

    // --- the same over project source, in each way the lambda can be written ---

    @Test
    fun everySpellingOfTheConstructorCallKeepsTheReceiver() {
        val src = sourceProject()
        val cases = mapOf(
            "named argument + explicit lambda parameter" to
                "Painter(id = \"x\", paint = { ctx: PaintCtx -> drawRoundRect(1) })",
            "positional arguments" to "Painter(\"x\", 1, { ctx: PaintCtx -> drawRoundRect(1) })",
            "trailing lambda" to "Painter(\"x\", 1) { ctx: PaintCtx -> drawRoundRect(1) }",
            "receiver-only function type" to "Simple { drawRoundRect(1) }",
            "implicit `it`" to "OneArg(paint = { drawRoundRect(1) })",
        )
        for ((label, call) in cases) {
            val u = unresolved(src, "package demo\nfun reg() { $call }")
            assertTrue(u.isEmpty(), "$label: the block's receiver members must resolve; got $u")
        }
    }

    @Test
    fun theReceiversMembersAreOfferedInCompletion() {
        // Proof the receiver is actually established, not just the diagnostic silenced.
        val src = sourceProject()
        val analyzer = KotlinSourceAnalyzer(fakeContext(src))
        val r = runBlocking {
            analyzer.completeAtCaret(src, "Use.kt", "package demo\nfun reg() { Simple { drawRound| } }")
        }
        assertTrue(
            r.items.any { it.symbol?.name == "drawRoundRect" },
            "the receiver's members must complete; got ${r.items.map { it.label }}",
        )
    }

    @Test
    fun aNonFunctionalParameterIsUnaffected() {
        // A constructor whose lambda slot ISN'T a receiver type gains nothing and loses nothing.
        val u = unresolved(sourceProject(), "package demo\nfun reg() { Plain { drawRoundRect(1) } }")
        assertFalse(u.isEmpty(), "a plain `() -> Unit` block has no receiver, so the call is unresolved; got $u")
    }

    private fun sourceProject(): Path = tempProject(
        mapOf(
            "Api.kt" to """
            package demo
            interface DrawScopeX { fun drawRoundRect(color: Int) }
            interface PaintCtx { val lineHeight: Float }
            class Painter(val id: String, val order: Int = 1000, val paint: DrawScopeX.(PaintCtx) -> Unit)
            class Simple(val paint: DrawScopeX.() -> Unit)
            class OneArg(val paint: DrawScopeX.(PaintCtx) -> Unit)
            class Plain(val paint: () -> Unit)
            """.trimIndent(),
        ),
    )

    /** Stage the compiled [dev.ide.fakecompose.FakePainter] fixture into a Kotlin-looking jar, so the
     *  constructor's receiver-lambda parameter arrives through a `@kotlin.Metadata` decode. */
    private fun fakePainterJar(): Path {
        val jar = Files.createTempFile("fake-painter", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakepainter.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakePainter.class")
            add("dev/ide/fakecompose/FakePaintScope.class")
            add("dev/ide/fakecompose/PaintColor.class")
            add("dev/ide/fakecompose/FakePaintContext.class")
        }
        return jar
    }
}
