package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Extension functions declared INSIDE an `object` (the "namespaced extensions" idiom) — reported as neither
 * suggested nor seen.
 *
 * Two halves. **Suggested:** such an extension is reachable only through `import util.StringUtils.twice`, and
 * nothing offered it on a matching receiver, so there was no way to discover it (or its import) from the
 * editor; completion now offers it with that import as its accept-time edit, and the "Import …" quick-fix on
 * the unresolved call offers the same container path. **Seen:** a member extension of a class's `companion
 * object` is in scope throughout that class's body (the companion is an implicit receiver there) but read as
 * `unresolved reference`, and a nested `object` named by its simple name from inside its enclosing class
 * didn't type as a value at all, so `with(Inner) { … }` resolved nothing in the block.
 */
class KotlinObjectMemberExtensionTest {

    private fun diagnostics(srcDir: Path, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking {
            analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics
        }.filter { it.code == "kt.unresolved" }.map { it.message }
    }

    private fun items(srcDir: Path, code: String): List<CompletionItem> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath())))
        return runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items
    }

    private fun item(items: List<CompletionItem>, name: String): CompletionItem =
        items.firstOrNull { it.symbol?.name == name }
            ?: error("no completion item named `$name`; got ${items.mapNotNull { it.symbol?.name }}")

    // --- suggested: the offer + its import ---

    @Test
    fun unimportedObjectExtensionIsOfferedWithItsImport() {
        val items = items(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".twi| }
            """.trimIndent(),
        )
        val edits = item(items, "twice").additionalEdits
        assertEquals(1, edits.size, "expected one import edit; got $edits")
        assertTrue(
            edits.single().newText.contains("import util.StringUtils.twice"),
            "the offer must import through its `object` container; got `${edits.single().newText}`",
        )
    }

    /** The extension-PROPERTY shape (`val String.doubled`) travels the same path. */
    @Test
    fun unimportedObjectExtensionPropertyIsOfferedWithItsImport() {
        val items = items(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".doub| }
            """.trimIndent(),
        )
        assertTrue(
            item(items, "doubled").additionalEdits.single().newText.contains("import util.StringUtils.doubled"),
            "an object's extension property must offer its container import too",
        )
    }

    /** Offering it is NOT resolving it: without the import the call must still be flagged, as Kotlin reports. */
    @Test
    fun theSameCallWithoutTheImportIsStillFlagged() {
        val u = diagnostics(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".twice() }
            """.trimIndent(),
        )
        assertTrue(u.any { it.contains("twice") }, "an un-imported object extension must stay unresolved; got $u")
    }

    /** …and accepting the offer (i.e. adding exactly that import) is what makes it resolve. */
    @Test
    fun withTheOfferedImportItResolves() {
        val u = diagnostics(
            utilProject(),
            """
            package demo
            import util.StringUtils.twice
            fun f() { val s = "a".twice() }
            """.trimIndent(),
        )
        assertTrue(u.none { it.contains("twice") }, "the offered import must make the call resolve; got $u")
    }

    /** A NESTED object's container path is the whole classifier chain (`util.Host.Inner`), not just the
     *  package plus the last segment. */
    @Test
    fun nestedObjectExtensionOffersItsFullContainerPath() {
        val items = items(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".fo| }
            """.trimIndent(),
        )
        assertTrue(
            item(items, "four").additionalEdits.single().newText.contains("import util.Host.Inner.four"),
            "a nested object's import must carry the full container chain",
        )
    }

    /** A companion object imports through the companion itself (`Host.Companion.comp`), the OkHttp spelling. */
    @Test
    fun companionExtensionOffersTheCompanionPath() {
        val items = items(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".co| }
            """.trimIndent(),
        )
        assertTrue(
            item(items, "comp").additionalEdits.single().newText.contains("import util.Host.Companion.comp"),
            "a companion member extension imports through the companion",
        )
    }

    /** Already imported → offered exactly once, and with no second import line. */
    @Test
    fun theAlreadyImportedOfferCarriesNoDuplicateImport() {
        val items = items(
            utilProject(),
            """
            package demo
            import util.StringUtils.twice
            fun f() { val s = "a".twi| }
            """.trimIndent(),
        )
        assertEquals(1, items.count { it.symbol?.name == "twice" }, "one item, not one per source")
        assertTrue(item(items, "twice").additionalEdits.isEmpty(), "already imported → no import edit")
    }

    /** A plain CLASS's member extension can never be imported, so it must not be offered on the receiver. */
    @Test
    fun aPlainClassMemberExtensionIsNotOffered() {
        val names = items(
            utilProject(),
            """
            package demo
            fun f() { val s = "a".sec| }
            """.trimIndent(),
        ).mapNotNull { it.symbol?.name }
        assertFalse("secret" in names, "a non-singleton container's member extension is not reachable; got $names")
    }

    /** The "Import …" quick-fix on the unresolved call offers the container path, for a plain `object` too
     *  (it used to cover only companions). */
    @Test
    fun theImportQuickFixOffersTheObjectContainer() {
        val service = KotlinSymbolService(listOf(DiskFile(utilProject())), listOf(stdlibJarPath()), null)
        val candidates = service.importCandidates("twice")
        assertTrue("util.StringUtils.twice" in candidates, "expected the object-container import; got $candidates")
    }

    // --- seen: companion + nested-object scope ---

    @Test
    fun companionMemberExtensionResolvesInTheEnclosingClassBody() {
        val u = diagnostics(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            class Host {
                val eager: String = "a".comp()
                companion object { fun String.comp(): String = this }
                fun g(): String = "b".comp()
            }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a companion's member extension is in scope in the class body; got $u")
    }

    @Test
    fun companionMemberExtensionResolvesInASubclassBody() {
        val u = diagnostics(
            tempProject(
                mapOf(
                    "Base.kt" to """
                    package demo
                    open class Base { companion object { fun String.comp(): String = this } }
                    """.trimIndent(),
                ),
            ),
            """
            package demo
            class Derived : Base() { fun g(): String = "a".comp() }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a supertype companion's member extension is bare-accessible too; got $u")
    }

    @Test
    fun companionMemberExtensionCompletesInTheEnclosingClassBody() {
        val names = items(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            class Host {
                companion object { fun String.comp(): String = this }
                fun g(): String = "a".co|
            }
            """.trimIndent(),
        ).mapNotNull { it.symbol?.name }
        assertTrue("comp" in names, "it is in scope, so it must complete; got $names")
    }

    /** A nested `object` named by its SIMPLE name inside its enclosing class denotes the singleton, so
     *  `with(Inner) { … }` has a receiver — its members and its member extensions both resolve in the block. */
    @Test
    fun nestedObjectByItsSimpleNameTypesAsAValue() {
        val u = diagnostics(
            tempProject(mapOf("Seed.kt" to "package demo\n")),
            """
            package demo
            class Host {
                object Inner {
                    fun plain(): String = ""
                    fun String.four(): String = this
                }
                fun g(): String = with(Inner) { plain() + "a".four() }
            }
            """.trimIndent(),
        )
        assertTrue(u.isEmpty(), "a nested object reached by simple name must type as a value; got $u")
    }

    /** A project with an `object` holding two extensions, a plain class holding one, in package `util`. */
    private fun utilProject(): Path = tempProject(
        mapOf(
            "Utils.kt" to """
            package util
            object StringUtils {
                fun String.twice(): String = this + this
                val String.doubled: String get() = this + this
            }
            class Holder {
                fun String.secret(): String = this
            }
            class Host {
                object Inner { fun String.four(): String = this }
                companion object { fun String.comp(): String = this }
            }
            """.trimIndent(),
        ),
    )
}
