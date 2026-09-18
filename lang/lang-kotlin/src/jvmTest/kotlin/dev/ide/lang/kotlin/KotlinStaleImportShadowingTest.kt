package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A stale/typo'd explicit import whose target does NOT exist must not shadow a same-file (or same-package)
 * declaration of the same simple name. `import com.example.ordersystem.models.Food` (a package that isn't on
 * the classpath) sitting next to a local `data class Food()` made `Food` resolve to the missing imported type
 * — an UNKNOWN receiver — so member-access diagnostics backed off and `food.name`/`food.pinyin` on the empty
 * data class were silently accepted (the reported "why aren't we showing errors on property access on food?").
 * Kotlin resolves `Food` to the local class here (and flags the import), so the member accesses must error.
 */
class KotlinStaleImportShadowingTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun staleImportDoesNotShadowLocalDeclaration() {
        // The imported `com.example.ordersystem.models.Food` doesn't exist → `Food` must bind to the local empty
        // `data class Food()`, so `food.name` (no such property) is flagged.
        val diags = diagnose(
            "Order.kt",
            "package demo\n" +
                "import com.example.ordersystem.models.Food\n" +
                "data class Food()\n" +
                "fun use(xs: MutableList<Food>) {\n" +
                "  xs.filter { food -> food.name.isNotEmpty() || food.pinyin.isNotEmpty() }\n" +
                "}\n"
        )
        val unresolved = diags.filter { it.code == "kt.unresolved" }.map { it.message }
        assertTrue(
            unresolved.any { "name" in it } && unresolved.any { "pinyin" in it },
            "member access on the local empty Food must be flagged, not suppressed by the missing import; got $diags",
        )
    }

    @Test
    fun unknownImportWithNoLocalDoesNotFalseFlagMembers() {
        // No same-named local declaration to fall back to: the type stays genuinely unknown, so member-access
        // checks must still BACK OFF (the analyzer can't enumerate an unseen type). Guards that the fix only
        // stops an unknown import from shadowing a REAL local declaration — it must not turn member access on a
        // truly-unknown imported type into a false "unresolved".
        val diags = diagnose(
            "Missing.kt",
            "package demo\n" +
                "import com.example.ordersystem.models.Widget\n" +
                "fun use(w: Widget) { w.whatever() }\n"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "whatever" in it.message },
            "member access on a truly-unknown imported type must back off, not false-flag; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
