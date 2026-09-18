package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An infix call whose function is not in scope must be flagged, the way the dotted spelling of the same call
 * already was. Reported for Compose's `enter togetherWith exit` used without
 * `import androidx.compose.animation.togetherWith`: the call compiled to nothing and the editor said nothing.
 *
 * The cause is structural. An infix operation reference is a `KtOperationReferenceExpression`, a SIBLING of
 * `KtNameReferenceExpression` rather than a subclass, so the checker registered for name references never
 * visited one. Every infix call was therefore exempt from the unresolved-reference check, including a plain
 * typo. Most of this class pins the back-offs instead, since a false "unresolved" on working code is worse
 * than the missing flag was.
 */
class KotlinInfixUnresolvedTest {

    private fun unresolved(srcDir: Path, code: String, jars: List<Path> = listOf(stdlibJarPath())): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, jars))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
        }.filter { it.code == "kt.unresolved" }.map { it.message }
    }

    // --- the report: a library (classpath) infix extension, reached the way Compose intends ---

    @Test
    fun aLibraryInfixExtensionWithoutItsImportIsFlagged() {
        val u = unresolved(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            import dev.ide.fakecompose.FakeEnter
            import dev.ide.fakecompose.FakeExit

            fun f(e: FakeEnter, x: FakeExit) {
                val t = e fakeTogetherWith x
                println(t)
            }
            """.trimIndent(),
            listOf(fakeAnimJar(), stdlibJarPath()),
        )
        assertTrue(
            u.any { it.contains("fakeTogetherWith") },
            "an infix extension that is not imported does not resolve, so it must be flagged; got $u",
        )
    }

    @Test
    fun theSameCallWithTheImportIsClean() {
        val u = unresolved(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            import dev.ide.fakecompose.FakeEnter
            import dev.ide.fakecompose.FakeExit
            import dev.ide.fakecompose.fakeTogetherWith

            fun f(e: FakeEnter, x: FakeExit) {
                val t = e fakeTogetherWith x
                println(t)
            }
            """.trimIndent(),
            listOf(fakeAnimJar(), stdlibJarPath()),
        )
        assertTrue(u.isEmpty(), "the import brings it into scope; got $u")
    }

    /** A name that exists nowhere was equally exempt, so a typo'd infix call read as fine. */
    @Test
    fun anInfixNameThatExistsNowhereIsFlagged() {
        val u = unresolved(
            animProject(),
            """
            package demo
            fun f() { println("a" frobnicate "b") }
            """.trimIndent(),
        )
        assertTrue(u.any { it.contains("frobnicate") }, "expected an unresolved reference; got $u")
    }

    @Test
    fun aProjectSourceInfixExtensionWithoutItsImportIsFlagged() {
        val u = unresolved(
            animProject(),
            """
            package demo
            import anim.Enter
            import anim.Exit
            fun f(e: Enter, x: Exit) { println(e togetherWith x) }
            """.trimIndent(),
        )
        assertTrue(u.any { it.contains("togetherWith") }, "a source extension needs its import too; got $u")
    }

    /** The Import fix reads the name out of the diagnostic's range, so it works on an operation reference too. */
    @Test
    fun theImportQuickFixOffersTheInfixExtension() {
        val srcDir = animProject()
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        val code = "package demo\nimport anim.Enter\nimport anim.Exit\nfun f(e: Enter, x: Exit) { println(e togetherWith x) }\n"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        val fixes = runBlocking {
            analyzer.incrementalParser.parseFull(doc)
            analyzer.analyze(doc.file)
            analyzer.importFixesAt(doc.file, code.indexOf("togetherWith") + 1)
        }
        assertTrue(
            fixes.any { it.title == "Import anim.togetherWith" },
            "expected the import fix on the operation reference; got ${fixes.map { it.title }}",
        )
    }

    // --- back-offs: none of these may be flagged ---

    @Test
    fun aMemberInfixNeedsNoImport() {
        val u = unresolved(
            animProject(),
            """
            package demo
            import anim.Holder
            fun f(h: Holder) { println(h member "x") }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a member infix is in scope through its receiver; got $u")
    }

    @Test
    fun theStdlibInfixesStayClean() {
        val u = unresolved(
            animProject(),
            """
            package demo
            fun f(n: Int, s: Set<Int>) {
                println("a" to 1)
                println(n and 3); println(n or 1); println(n shl 2); println(n xor 7)
                println(0 until n); println(n downTo 0); println((0 until n) step 2)
                println(s union setOf(1)); println(s intersect setOf(2))
            }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "default-imported stdlib infixes need no import; got $u")
    }

    @Test
    fun sameFileAndSamePackageDeclarationsAreClean() {
        val srcDir = animProject()
        assertTrue(
            unresolved(
                srcDir,
                """
                package demo
                infix fun String.twice2(o: String): String = this + o
                fun f() { println("a" twice2 "b") }
                """.trimIndent(),
            ).isEmpty(),
            "a same-file extension needs no import",
        )
        assertTrue(
            unresolved(
                srcDir,
                """
                package anim
                fun f(e: Enter, x: Exit) { println(e togetherWith x) }
                """.trimIndent(),
            ).isEmpty(),
            "a same-package extension needs no import",
        )
    }

    @Test
    fun aStarImportOfThePackageIsClean() {
        val u = unresolved(
            animProject(),
            """
            package demo
            import anim.*
            fun f(e: Enter, x: Exit) { println(e togetherWith x) }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a star import brings it into scope; got $u")
    }

    /** An alias renames the callable, so no lookup by the written name can find it: back off, never flag. */
    @Test
    fun anAliasedImportIsNotFlagged() {
        val u = unresolved(
            animProject(),
            """
            package demo
            import anim.Enter
            import anim.Exit
            import anim.togetherWith as combine
            fun f(e: Enter, x: Exit) { println(e combine x) }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "an aliased infix extension resolves; got $u")
    }

    @Test
    fun aLocalInfixExtensionIsClean() {
        val u = unresolved(
            animProject(),
            """
            package demo
            fun f() {
                infix fun String.loc(o: String): String = this + o
                println("a" loc "b")
            }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a local extension is in scope where it is declared; got $u")
    }

    @Test
    fun anUnknownLeftOperandIsNotFlagged() {
        val u = unresolved(
            animProject(),
            """
            package demo
            fun f(u: Unknown) { println(u mystery "b") }
            """.trimIndent(),
        )
        assertTrue(
            u.none { it.contains("mystery") },
            "an unenumerable receiver cannot judge the call (only `Unknown` itself is flagged); got $u",
        )
    }

    // --- fixtures ---

    private fun animProject(): Path = tempProject(
        mapOf(
            "Anim.kt" to """
            package anim
            class Enter
            class Exit
            class Transform
            infix fun Enter.togetherWith(exit: Exit): Transform = Transform()
            class Holder { infix fun member(o: String): String = o }
            """.trimIndent(),
        ),
    )

    /** Stage the compiled [dev.ide.fakecompose.FakeEnter] fixture into a Kotlin-looking jar, so the infix
     *  extension arrives through a `@kotlin.Metadata` decode exactly as a library's does. */
    private fun fakeAnimJar(): Path {
        val jar = Files.createTempFile("fake-anim", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakeanim.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeAnimKt.class")
            add("dev/ide/fakecompose/FakeEnter.class")
            add("dev/ide/fakecompose/FakeExit.class")
            add("dev/ide/fakecompose/FakeContentTransform.class")
        }
        return jar
    }
}
