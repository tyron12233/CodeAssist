package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RECALL: the errors the editor is expected to catch, held next to the code it must leave alone.
 *
 * The sweeps and the idiom tests measure one direction only, a wrong report on correct code. Nothing measured
 * the other one, so the checks grew precise while staying quiet on about half of ordinary compile errors
 * (`f(null)` for a `String` parameter, `val x: Int = 1L`, `break` outside a loop, a private member reached
 * from another class). `recall/must-error.txt` is that list, each snippet with the code it must produce, and
 * `recall/must-be-clean.txt` is the valid code written to look like each of those errors.
 *
 * Both run against the stdlib, the JDK and kotlinx-coroutines, through ONE analyzer: each snippet is a file
 * in a package of its own, so none can lean on another's declarations, and the two hundred of them do not
 * stand up two hundred classpaths in the JVM the rest of the suite shares.
 */
class KotlinErrorRecallTest {

    @Test
    fun everyKnownErrorIsReportedWithItsCode() {
        val missed = ArrayList<String>()
        for ((header, code) in blocks("must-error.txt")) {
            val wanted = header.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            assertTrue(wanted.isNotEmpty(), "a must-error snippet needs the code it expects:\n$code")
            val got = errors(code).mapNotNull { it.code }
            if (wanted.none { it in got }) missed += "expected one of $wanted, got $got:\n    ${code.replace("\n", "\n    ")}"
        }
        assertTrue(missed.isEmpty(), "${missed.size} error(s) went unreported:\n" + missed.joinToString("\n"))
    }

    @Test
    fun nothingValidIsReported() {
        val wrong = ArrayList<String>()
        for ((name, code) in blocks("must-be-clean.txt")) {
            val text = "package r\n$code\n"
            for (d in errors(code)) {
                val line = text.take(d.range.start.coerceIn(0, text.length)).count { it == '\n' }
                wrong += "[$name] ${d.code}: ${d.message}\n    | ${text.lineSequence().elementAtOrNull(line)?.trim()}"
            }
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} false positive(s):\n" + wrong.joinToString("\n"))
    }

    /** A class one file over is neither bytecode nor the tree in hand, and its constructor calls were checked
     *  by neither path: a wrong argument type and a wrong count both passed. */
    @Test
    fun aConstructorOfAProjectClassInAnotherFileIsChecked() {
        val model = "package shop\ndata class Item(val id: Long, val title: String, val done: Boolean = false)\n" +
            "class Cart(val items: List<Item>) { constructor() : this(emptyList()) }\n" +
            "class Tag(val name: String) { companion object { operator fun invoke(n: Int) = Tag(n.toString()) } }\n"
        fun codes(use: String): List<String?> = runBlocking {
            val text = "package shop.ui\nimport shop.*\n$use\n"
            val src = tempProject(mapOf("shop/Model.kt" to model, "shop/ui/Use.kt" to text))
            val a = KotlinSourceAnalyzer(fakeContext(src, listOf(stdlibJarPath())))
            val doc = SnippetDoc(text, DiskFile(src.resolve("shop/ui/Use.kt")))
            a.incrementalParser.parseFull(doc)
            a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR }.map { it.code }
        }
        assertTrue(KotlinDiagnosticCodes.TYPE_MISMATCH in codes("fun f() = Item(\"id\", \"t\")"))
        assertTrue(KotlinDiagnosticCodes.CONSTRUCTOR_ARGS in codes("fun f() = Item(1L)"))
        assertTrue(KotlinDiagnosticCodes.CONSTRUCTOR_ARGS in codes("fun f() = Cart(1, 2)"))
        val clean = codes(
            "fun f() = listOf(Item(1L, \"t\"), Item(2, \"u\", true), Item(id = 3, title = \"v\"))\n" +
                "fun g() = Cart().items.size + Cart(emptyList()).items.size + Tag(1).name.length + Tag(\"x\").name.length",
        )
        assertTrue(clean.isEmpty(), "valid constructor calls were reported: $clean")
    }

    /** A corpus file as (header, snippet) pairs: whatever follows `////` on its line, then the lines up to the next. */
    private fun blocks(name: String): List<Pair<String, String>> {
        val text = File(repoRoot(), "lang/lang-kotlin/recall/$name").readText().replace("\r\n", "\n")
        val parts = text.split(Regex("(?m)^////"))
        return parts.drop(1).map { part ->
            part.substringBefore('\n').trim() to part.substringAfter('\n', "").trim()
        }.filter { it.second.isNotEmpty() }
    }

    /** The errors in [code], analyzed as its own file in its own package. Parsed immediately before it is
     *  analyzed: the analyzer keeps only its most recent parses, and an evicted file analyzes as empty. */
    private fun errors(code: String): List<Diagnostic> = runBlocking {
        val n = ++counter
        val doc = SnippetDoc("package r$n\n$code\n", DiskFile(srcDir.resolve("r$n/Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR }
    }

    private var counter = 0

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("no settings.gradle.kts above ${System.getProperty("user.dir")}")
    }

    // Per test instance, NOT in the companion: an analyzer over the JDK holds its whole index, and one parked
    // in a static for the life of the test JVM is heap every later test class runs without.
    private val srcDir: Path = tempProject(mapOf("Marker.kt" to "package recall\nclass Marker"))
    private val analyzer by lazy { KotlinSourceAnalyzer(fakeContext(srcDir, jars)) }

    companion object {
        private val jars: List<Path> by lazy {
            val coroutines = System.getProperty("java.class.path").split(File.pathSeparator).first { entry ->
                entry.endsWith(".jar") &&
                    runCatching { ZipFile(entry).use { it.getEntry("kotlinx/coroutines/Deferred.class") != null } }.getOrDefault(false)
            }
            listOfNotNull(
                stdlibJarPath(), Path.of(coroutines),
                TestJars.jdkBaseJar(Path.of(System.getProperty("java.io.tmpdir"), "codeassist-jdk-jar")),
            )
        }
    }
}
