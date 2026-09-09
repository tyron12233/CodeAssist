package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import dev.ide.lang.synthetic.SyntheticMethod
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A synthetic ("light") class the host contributes (Android `R`/`BuildConfig`, a ViewBinding) obeys the same
 * scope rule as a source class: bare from its own package, an import anywhere else.
 *
 * It used to resolve by simple name from ANY package, so `R.string.app_name` in a subpackage looked fine in
 * the editor and then failed to compile with "Unresolved reference: R" - the case with the least excuse for
 * the editor to miss it, since the resource is right there in the project.
 */
class KotlinSyntheticImportScopeTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(fileName: String, code: String) =
        diagnose(fileName, code).filter { it.code == "kt.unresolved" }

    @Test
    fun syntheticClassOutsideItsPackageNeedsAnImport() {
        for ((name, code) in listOf(
            "R" to "package demo.ui\nfun f(): Int = R.string.app_name",
            "R" to "package other\nfun f(): Int = R.string.app_name",
            "R" to "package demo.ui\nimport other.*\nfun f(): Int = R.string.app_name",   // an unrelated star import
            "R" to "package demo.ui\nfun g(id: Int) {}\nfun f() { g(R.string.app_name) }",
            "BuildConfig" to "package demo.ui\nfun f(): Boolean = BuildConfig.DEBUG",
            "ActivityMainBinding" to "package demo.ui\nfun f() { ActivityMainBinding.inflate() }",
            "ActivityMainBinding" to "package demo.ui\nfun f(b: ActivityMainBinding) { }",  // type position
        )) {
            val d = unresolved("ui/B.kt", code)
            assertTrue(
                d.any { it.message.contains("Unresolved reference: $name") },
                "`$name` used outside its package with no import should be flagged; got $d for:\n$code",
            )
        }
    }

    @Test
    fun syntheticClassInScopeIsNotFlagged() {
        for (code in listOf(
            "package demo\nfun f(): Int = R.string.app_name",                              // its own package
            "package demo\nfun f(): Boolean = BuildConfig.DEBUG",
            "package demo.ui\nimport demo.R\nfun f(): Int = R.string.app_name",            // explicit import
            "package demo.ui\nimport demo.*\nfun f(): Int = R.string.app_name",            // star import
            "package demo.ui\nfun f(): Int = demo.R.string.app_name",                      // fully qualified
            "package demo.ui\nimport demo.databinding.ActivityMainBinding\nfun f() { ActivityMainBinding.inflate() }",
        )) {
            val d = unresolved("ui/B.kt", code)
            assertTrue(d.isEmpty(), "an in-scope synthetic class must not be flagged; got $d for:\n$code")
        }
    }

    @Test
    fun membersOfAnInScopeSyntheticClassAreStillChecked() {
        assertTrue(
            unresolved("A.kt", "package demo\nfun f(): Int = R.string.nope").any { it.message.contains("nope") },
            "an unknown resource name should still be flagged",
        )
        assertTrue(
            unresolved("A.kt", "package demo\nfun f(): Int = R.nope.app_name").any { it.message.contains("nope") },
            "an unknown resource type should still be flagged",
        )
    }

    @Test
    fun theMissingImportIsOfferedAsAQuickFix() {
        for ((needle, fqn, code) in listOf(
            Triple("R.string", "demo.R", "package demo.ui\nfun f(): Int = R.string.app_name"),
            Triple("BuildConfig", "demo.BuildConfig", "package demo.ui\nfun f(): Boolean = BuildConfig.DEBUG"),
            Triple(
                "ActivityMainBinding", "demo.databinding.ActivityMainBinding",
                "package demo.ui\nfun f() { ActivityMainBinding.inflate() }",
            ),
        )) {
            val doc = SnippetDoc(code, DiskFile(srcDir.resolve("ui/B.kt")))
            runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            val titles = analyzer.importFixesAt(doc.file, code.indexOf(needle) + 1).map { it.title }
            assertTrue("Import $fqn" in titles, "the lightbulb should offer `Import $fqn`; got $titles")
        }
    }

    @Test
    fun completionStillOffersASyntheticClassFromAnotherPackage() {
        // Flagging the use must not stop completion proposing it: accepting the item adds the import.
        val labels = runBlocking {
            analyzer.completeAtCaret(srcDir, "ui/B.kt", "package demo.ui\nfun f() { R| }")
        }.items.map { it.label }
        assertTrue("R" in labels, "`R` must still complete from a subpackage; got ${labels.take(20)}")
    }

    companion object {
        // demo.R / demo.BuildConfig / demo.databinding.* as the Android plugin contributes them for a module
        // whose namespace is `demo` (host-injected, NOT real classpath types).
        private val R_CLASS = SyntheticClass(
            fqName = "demo.R",
            nestedClasses = listOf(
                SyntheticClass(fqName = "demo.R.string", fields = listOf(SyntheticField("app_name"))),
                SyntheticClass(fqName = "demo.R.layout", fields = listOf(SyntheticField("activity_main"))),
            ),
            doc = "Resource identifiers (synthetic R)",
        )
        private val BUILD_CONFIG =
            SyntheticClass("demo.BuildConfig", fields = listOf(SyntheticField("DEBUG", type = "boolean")))
        private val BINDING = SyntheticClass(
            "demo.databinding.ActivityMainBinding",
            methods = listOf(SyntheticMethod("inflate", returnType = "demo.databinding.ActivityMainBinding")),
        )

        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\nfun seed() {}"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
            .also { it.syntheticClassProvider = { listOf(R_CLASS, BUILD_CONFIG, BINDING) } }
    }
}
