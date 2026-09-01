package dev.ide.lang.kotlin

import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.index.PackagesIndex
import dev.ide.lang.dom.Diagnostic
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renaming a declaration must leave its stale usages marked "Unresolved reference".
 *
 * Reads in VALUE position (`g(original)`, `val y = original`) were already reported. Reads in RECEIVER
 * position (`original.length`, `binding.root()`) were NOT, and that is the position most usages of a variable
 * actually take, so a rename looked clean in the editor and failed at build time instead. Neither check could
 * see it: [KotlinSemanticChecks.unresolvedMember] backs off because it cannot type an unresolved receiver, and
 * the bare-reference check treated every unknown receiver as innocent, since a receiver may also be a package
 * qualifier or a generated class. It now separates those by Kotlin's own naming convention (see
 * `danglingValueReceiver`), which is what these cases pin down.
 */
class KotlinRenamedVariableUsageTest {

    @Test
    fun localValUsageIsFlaggedAfterTheDeclarationIsRenamed() {
        assertFlagsOriginal(
            afterRename(
                "package demo\nfun g(p: Int) {}\nfun f() {\n  val original = 1\n  g(original)\n}\n",
                "package demo\nfun g(p: Int) {}\nfun f() {\n  val renamed = 1\n  g(original)\n}\n",
            )
        )
    }

    @Test
    fun parameterUsageIsFlaggedAfterTheParameterIsRenamed() {
        assertFlagsOriginal(
            afterRename(
                "package demo\nfun g(p: Int) {}\nfun f(original: Int) {\n  g(original)\n}\n",
                "package demo\nfun g(p: Int) {}\nfun f(renamed: Int) {\n  g(original)\n}\n",
            )
        )
    }

    @Test
    fun parameterUsageInsideALambdaIsFlaggedAfterTheParameterIsRenamed() {
        // The edit is in the function HEADER while the lambda's text is untouched, so this is also the guard
        // that the per-statement incremental reuse cannot serve a stale clean result for the body.
        assertFlagsOriginal(
            afterRename(
                "package demo\nfun g(p: Int) {}\nfun f(original: Int) {\n  listOf(1).forEach { g(original) }\n}\n",
                "package demo\nfun g(p: Int) {}\nfun f(renamed: Int) {\n  listOf(1).forEach { g(original) }\n}\n",
            )
        )
    }

    @Test
    fun topLevelPropertyUsageInAnotherFunctionIsFlaggedAfterTheRename() {
        assertFlagsOriginal(
            afterRename(
                "package demo\nval original = 1\nfun g(p: Int) {}\nfun f() {\n  g(original)\n}\n",
                "package demo\nval renamed = 1\nfun g(p: Int) {}\nfun f() {\n  g(original)\n}\n",
            )
        )
    }

    // --- receiver position: the shape that used to go unreported ------------------------------------------

    @Test
    fun memberReadOffARenamedLocalIsFlagged() =
        assertFlagsOriginal(diagnose("package demo\nfun f() {\n  val renamed = \"s\"\n  val n = original.length\n}\n"))

    @Test
    fun methodCallOnARenamedLocalIsFlagged() =
        assertFlagsOriginal(diagnose("package demo\nfun f() {\n  val renamed = \"s\"\n  original.trim()\n}\n"))

    @Test
    fun memberReadOffARenamedParameterIsFlagged() =
        assertFlagsOriginal(diagnose("package demo\nfun f(renamed: String) {\n  val n = original.length\n}\n"))

    @Test
    fun memberReadOffARenamedParameterInsideALambdaIsFlagged() =
        assertFlagsOriginal(
            diagnose("package demo\nfun f(renamed: String) {\n  listOf(1).forEach { val n = original.length }\n}\n")
        )

    @Test
    fun safeCallAndChainedCallOnARenamedValueAreFlagged() {
        assertFlagsOriginal(diagnose("package demo\nfun f(renamed: String) {\n  val n = original?.length\n}\n"))
        assertFlagsOriginal(diagnose("package demo\nfun f(renamed: String) {\n  val n = original.trim().length\n}\n"))
    }

    // --- the receiver shapes that must stay clean ---------------------------------------------------------

    @Test
    fun declaredValuesUsedAsReceiversAreNotFlagged() {
        // Every way a lower-case name legitimately comes into scope. If any of these regressed, valid code would
        // light up red, which is far worse than the missing report this check exists to fix.
        for ((label, code) in listOf(
            "local" to "package demo\nfun f() {\n  val user = \"s\"\n  val n = user.length\n}\n",
            "parameter" to "package demo\nfun f(user: String) {\n  val n = user.length\n}\n",
            "lambda it" to "package demo\nfun f(xs: List<String>) {\n  xs.forEach { val n = it.length }\n}\n",
            "lambda param" to "package demo\nfun f(xs: List<String>) {\n  xs.forEach { s -> val n = s.length }\n}\n",
            "for variable" to "package demo\nfun f(xs: List<String>) {\n  for (s in xs) { val n = s.length }\n}\n",
            "catch param" to "package demo\nfun f() {\n  try { } catch (e: RuntimeException) { val m = e.message }\n}\n",
            "destructured" to "package demo\nfun f(p: Pair<String, String>) {\n  val (a, b) = p\n  val n = a.length\n}\n",
            "when subject" to "package demo\nfun f() {\n  when (val v = \"s\") { else -> v.length }\n}\n",
            "class property" to "package demo\nclass C {\n  val user = \"s\"\n  fun f() { val n = user.length }\n}\n",
            "lateinit property" to "package demo\nclass C {\n  lateinit var binding: String\n  fun f() { val n = binding.length }\n}\n",
            "constructor val" to "package demo\nclass C(val user: String) {\n  fun f() { val n = user.length }\n}\n",
            "other file" to "package demo\nfun f() {\n  val n = otherTopLevel.length\n}\n",
        )) {
            val u = unresolved(diagnose(code))
            assertTrue(
                u.none { m -> RECEIVERS.any { m.endsWith(": $it") } },
                "a declared receiver ($label) must not be flagged; got $u",
            )
        }
    }

    @Test
    fun packageQualifiersAreNotFlagged() {
        for ((label, code) in listOf(
            "known root package" to "package demo\nfun f() {\n  val x = kotlin.math.max(1, 2)\n}\n",
            "known root package, type" to "package demo\nfun f() {\n  val x = java.util.Locale.US\n}\n",
            // The safety valve for a package the index does not happen to hold: the chain is spelled to a
            // Capitalized TYPE, which no value chain does.
            "unknown package to a type" to "package demo\nfun f() {\n  val x = unknownpkg.Foo.bar\n}\n",
            "segment named by an import" to "package demo\nimport myorg.tools.Helper\nfun f() {\n  val x = tools.Helper\n}\n",
        )) {
            val u = unresolved(diagnose(code))
            assertTrue(
                u.none { it.endsWith(": kotlin") || it.endsWith(": java") || it.endsWith(": unknownpkg") || it.endsWith(": tools") },
                "a package qualifier ($label) must not be flagged; got $u",
            )
        }
    }

    @Test
    fun generatedCapitalizedReceiversAreNotFlagged() {
        // `R` / `BuildConfig` / a ViewBinding class may not be built yet, so a Capitalized receiver with no
        // positive evidence keeps its existing treatment.
        for (code in listOf(
            "package demo\nfun f() {\n  val x = R.string.app_name\n}\n",
            "package demo\nfun f() {\n  val x = BuildConfig.DEBUG\n}\n",
            "package demo\nfun f() {\n  val x = ActivityMainBinding.inflate()\n}\n",
        )) {
            val u = unresolved(diagnose(code))
            assertTrue(
                u.none { it.endsWith(": R") || it.endsWith(": BuildConfig") || it.endsWith(": ActivityMainBinding") },
                "a generated Capitalized receiver must not be flagged; got $u",
            )
        }
    }

    // --- harness ------------------------------------------------------------------------------------------

    private fun analyzerOver(src: String): Pair<KotlinSourceAnalyzer, DiskFile> {
        val dir = tempProject(mapOf("Main.kt" to src, "Other.kt" to "package demo\nval otherTopLevel = \"s\"\n"))
        return KotlinSourceAnalyzer(fakeContext(dir)).apply { indexService = readyIndex } to
            DiskFile(dir.resolve("Main.kt"))
    }

    private fun diagnose(src: String): List<Diagnostic> {
        val (analyzer, file) = analyzerOver(src)
        return runBlocking {
            analyzer.incrementalParser.parseFull(SnippetDoc(src, file, 1))
            analyzer.analyze(file).diagnostics
        }
    }

    /** Analyze [before], then re-analyze [after] on the SAME analyzer, so the incremental caches are in play. */
    private fun afterRename(before: String, after: String): List<Diagnostic> {
        val (analyzer, file) = analyzerOver(before)
        return runBlocking {
            analyzer.incrementalParser.parseFull(SnippetDoc(before, file, 1))
            analyzer.analyze(file)
            analyzer.incrementalParser.parseFull(SnippetDoc(after, file, 2))
            analyzer.analyze(file).diagnostics
        }
    }

    private fun unresolved(diags: List<Diagnostic>) = diags.filter { it.code == "kt.unresolved" }.map { it.message }

    private fun assertFlagsOriginal(diags: List<Diagnostic>) {
        val u = unresolved(diags)
        assertTrue(u.any { it.endsWith(": original") }, "the stale usage of 'original' must be flagged; got $u")
    }

    private companion object {
        /** The receiver names used by [declaredValuesUsedAsReceiversAreNotFlagged]. */
        val RECEIVERS = listOf("user", "it", "s", "e", "a", "v", "binding", "otherTopLevel")

        /** Root packages the fake index knows, so `isRootPackage` can answer the package cases. */
        val ROOT_PACKAGES = setOf(
            "kotlin", "kotlin.math", "kotlinx", "java", "java.util", "javax", "androidx", "demo", "myorg",
        )

        @Suppress("UNCHECKED_CAST")
        val readyIndex = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                (if (id in PackagesIndex.ALL && key in ROOT_PACKAGES) sequenceOf(key) else emptySequence())
                    as Sequence<V>
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }
    }
}
