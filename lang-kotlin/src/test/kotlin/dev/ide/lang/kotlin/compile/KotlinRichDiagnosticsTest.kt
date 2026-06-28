package dev.ide.lang.kotlin.compile

import dev.ide.build.BuildDiagnostic
import dev.ide.build.BuildSeverity
import dev.ide.build.engine.CompilerOutputParser
import dev.ide.lang.kotlin.parse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin compiler's errors must reach the build console as *rich* diagnostics: located, navigable
 * problems, the same treatment ecj output gets — not opaque `[ERROR] …` log lines. This pins the contract
 * between [KotlinJvmCompiler]'s message format and build-engine's [CompilerOutputParser]: a real failing
 * compile produces messages the parser lifts into a located [BuildDiagnostic].
 */
class KotlinRichDiagnosticsTest {
    private val stdlib: Path = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())

    @BeforeTest
    fun pinParserHost() { parse("package warmup\nfun warmup() {}") }

    @Test
    fun compileErrorParsesIntoLocatedDiagnostic() {
        val dir = Files.createTempDirectory("kt-rich-diag")
        try {
            val src = dir.resolve("src"); val out = dir.resolve("out")
            val bad = src.resolve("demo/Bad.kt")
            Files.createDirectories(bad.parent)
            Files.writeString(bad, "package demo\nfun f(): Int = nope()\n")

            val r = IncrementalKotlinCompiler().compile(listOf(bad), emptyList(), listOf(stdlib), out)
            assertFalse(r.success, "a call to an undeclared function must fail to compile")

            // Feed the compiler output through the build-engine parser exactly as reportToolDiagnostics does.
            val diags = ArrayList<BuildDiagnostic>()
            val parser = CompilerOutputParser("kotlin")
            for (m in r.messages) for (line in m.lineSequence()) parser.accept(line) { diags.add(it) }
            parser.flush { diags.add(it) }

            val err = diags.firstOrNull { it.severity == BuildSeverity.ERROR }
            assertNotNull(err, "expected a structured ERROR diagnostic; raw messages were ${r.messages}")
            val loc = err.location
            assertNotNull(loc, "the diagnostic must carry a source location for navigation")
            assertTrue(loc.path.endsWith("Bad.kt"), "location should point at Bad.kt; was $loc")
            assertTrue(loc.line >= 1, "location should carry a 1-based line; was $loc")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
