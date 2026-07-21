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
 * The user report: `border = BorderStroke(2.dp, SolidColor(Color.Blue))` was flagged
 * "Type mismatch: inferred type is SolidColor but Color was expected". `BorderStroke` is a class with a
 * public constructor `(Dp, Brush)` AND a same-named factory function `(Dp, Color)`; `SolidColor` IS-A `Brush`,
 * so the call binds to the constructor. The applicability check judged only the FUNCTION overload (the
 * constructor was filtered out) and false-flagged the `SolidColor` argument against `color: Color`. A call
 * whose callee also resolves to a constructor must not be flagged on the function overloads alone.
 */
class KotlinConstructorFactoryOverloadTest {

    private fun diagnose(srcDir: Path, libJars: List<Path>, code: String): List<Diagnostic> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun mismatches(diags: List<Diagnostic>) =
        diags.filter { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH }

    @Test
    fun binaryConstructorOverloadArgumentNotFlagged() {
        // The exact report, on the binary (`@Metadata`) path: FakeBorderStroke has a `(Width, Brush)`
        // constructor + a `(Width, Color)` factory function; a `FakeSolidColor` (a `FakeBrush`) binds the ctor.
        val diags = diagnose(
            tempProject(emptyMap()), listOf(fakeBorderJar(), stdlibJarPath()),
            "import dev.ide.fakecompose.*\nfun f() { val b = FakeBorderStroke(FakeStrokeWidth(2), FakeSolidColor(FakeColor())) }",
        )
        assertTrue(
            mismatches(diags).isEmpty(),
            "a Brush subtype binding the constructor overload must not be flagged against the factory's Color param; got $diags",
        )
    }

    @Test
    fun sourceConstructorOverloadArgumentNotFlagged() {
        val srcDir = tempProject(
            mapOf(
                "Api.kt" to """
                    package demo
                    sealed class Brush
                    abstract class ShaderBrush : Brush()
                    class Color
                    class SolidColor(val value: Color) : ShaderBrush()
                    class Width(val px: Int)
                    class BorderStroke(val width: Width, val brush: Brush)
                    fun BorderStroke(width: Width, color: Color): BorderStroke = BorderStroke(width, SolidColor(color))
                """.trimIndent(),
            ),
        )
        val diags = diagnose(
            srcDir, listOf(stdlibJarPath()),
            "package demo\nfun f() { val b = BorderStroke(Width(2), SolidColor(Color())) }",
        )
        assertTrue(mismatches(diags).isEmpty(), "source constructor+factory overload must not be flagged; got $diags")
    }

    @Test
    fun overloadedFunctionWithoutSameNamedTypeStillFlagged() {
        // Guard against over-broadening the back-off: a capitalized callee that is NOT a type (no constructor
        // candidate) must still be judged by [callNotApplicable] — `Make("x")` fits no `Make` function overload.
        val srcDir = tempProject(
            mapOf("Api.kt" to "package demo\nfun Make(a: Int): Int = a\nfun Make(a: Int, b: Int): Int = a + b"),
        )
        val diags = diagnose(srcDir, listOf(stdlibJarPath()), "package demo\nfun f() { Make(\"x\") }")
        assertTrue(
            diags.any { it.code == KotlinDiagnosticCodes.TYPE_MISMATCH },
            "a wrong argument to an overloaded function with no same-named type must still be flagged; got $diags",
        )
    }

    private fun fakeBorderJar(): Path {
        val jar = Files.createTempFile("fake-border", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakeborder.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeBrush.class")
            add("dev/ide/fakecompose/FakeShaderBrush.class")
            add("dev/ide/fakecompose/FakeColor.class")
            add("dev/ide/fakecompose/FakeSolidColor.class")
            add("dev/ide/fakecompose/FakeStrokeWidth.class")
            add("dev/ide/fakecompose/FakeBorderStroke.class")
            add("dev/ide/fakecompose/FakeBorderStrokeKt.class")
        }
        return jar
    }
}
