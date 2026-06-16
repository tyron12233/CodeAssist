package dev.ide.lang.kotlin

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Completion for `this.`/`super.` (the implicit-receiver / supertype member access the resolver now types)
 * and for synthetic ("light") classes the host injects (the Android `R`), plus the invariant that a Kotlin
 * file does NOT see Kotlin file facades (the host excludes them — proven here by the provider seam).
 */
class KotlinThisSuperSyntheticTest {

    private fun labels(file: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun thisAccessSeesOwnAndInheritedMembers() {
        // `this` in Sub.f() is demo.Sub → its own members AND those inherited from Base.
        val items = labels("Sub.kt", SUB.replace("/*F*/", "this.|").replace("/*G*/", ""))
        assertTrue("own" in items, "own member 'own' via this; got ${items.take(20)}")
        assertTrue("baseMethod" in items, "inherited 'baseMethod' via this; got ${items.take(20)}")
        assertTrue("greet" in items, "inherited 'greet' via this; got ${items.take(20)}")
    }

    @Test
    fun superAccessSeesSupertypeMembersNotOwn() {
        // `super` is demo.Base → Base's members; Sub's own `own` must NOT appear.
        val items = labels("Sub.kt", SUB.replace("/*F*/", "").replace("/*G*/", "super.|"))
        assertTrue("baseMethod" in items, "inherited 'baseMethod' via super; got ${items.take(20)}")
        assertTrue("own" !in items, "Sub's own member must not appear via super; got ${items.take(20)}")
    }

    @Test
    fun thisAndSuperAreNotFlaggedUnresolved() {
        // `this`/`super` parse as name references but are keywords — they must never be flagged unresolved.
        val code = "package demo\nclass Sub : Base() {\n  val own = 1\n  fun f() { this.own\n    super.greet() }\n}"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Sub.kt")))
        val diags = runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        assertTrue(
            diags.none { it.code == "kt.unresolved" && (it.message.contains("super") || it.message.contains("this")) },
            "this/super must not be flagged unresolved; got $diags",
        )
    }

    @Test
    fun syntheticRClassResolves() {
        // `R.` → resource-type nested classes; `R.layout.` → the resource names; bare `R` completes as a type.
        val onR = labels("Use.kt", "package demo\nfun f() { R.| }")
        assertTrue("layout" in onR, "R.layout nested class; got $onR")
        assertTrue("string" in onR, "R.string nested class; got $onR")

        val onLayout = labels("Use.kt", "package demo\nfun f() { R.layout.| }")
        assertTrue("activity_main" in onLayout, "R.layout.activity_main; got $onLayout")
        assertTrue("item_row" in onLayout, "R.layout.item_row; got $onLayout")

        assertTrue("R" in labels("Use.kt", "package demo\nfun f() { R| }"), "bare R completes as a type")
    }

    @Test
    fun rFieldUsableInExpression() {
        // The field is typed (Int) — go-to-def style resolution + member usage should not error.
        val res = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun f() { R.layout.activity_| }") }
        val field = res.items.firstOrNull { it.symbol?.name == "activity_main" }
        assertNotNull(field, "R.layout.activity_main should be offered")
    }

    @Test
    fun kotlinFileFacadeIsNotSeen() {
        // The host excludes the Kotlin `<File>Kt` facades for the Kotlin backend; a default analyzer (empty
        // provider) therefore never surfaces a facade type, so `TopKt` is not a completion candidate.
        assertTrue("TopKt" !in labels("Use.kt", "package demo\nfun f() { TopK| }"), "no Kotlin file facade")
    }

    companion object {
        // demo.R as an Android plugin would contribute it (host-injected, NOT a real classpath type).
        private val R_CLASS = SyntheticClass(
            fqName = "demo.R",
            nestedClasses = listOf(
                SyntheticClass(fqName = "demo.R.layout", fields = listOf(SyntheticField("activity_main"), SyntheticField("item_row"))),
                SyntheticClass(fqName = "demo.R.string", fields = listOf(SyntheticField("app_name"))),
            ),
            doc = "Resource identifiers (synthetic R)",
        )

        private const val SUB =
            "package demo\nclass Sub : Base() {\n  val own = 1\n  fun f() { /*F*/ }\n  fun g() { /*G*/ }\n}"

        val srcDir: Path = tempProject(
            mapOf(
                "Base.kt" to "package demo\nopen class Base {\n  fun baseMethod(): String = \"x\"\n  open fun greet() {}\n}",
                // The disk copy of Sub (members the symbol model resolves for `this`); the caret snippets reuse it.
                "Sub.kt" to SUB.replace("/*F*/", "").replace("/*G*/", ""),
                "Top.kt" to "package demo\nfun topLevelFn() {}",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir)).also { it.syntheticClassProvider = { listOf(R_CLASS) } }
    }
}
