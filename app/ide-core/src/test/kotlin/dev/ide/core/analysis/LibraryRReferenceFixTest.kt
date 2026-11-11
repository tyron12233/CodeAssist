package dev.ide.core.analysis

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A resource read through a library's `R` that does not declare it gets the spellings that do resolve: the
 * declaring library's `R`, and the module's own.
 */
class LibraryRReferenceFixTest {

    private fun r(pkg: String, vararg typed: Pair<String, List<String>>) = SyntheticClass(
        fqName = "$pkg.R",
        nestedClasses = typed.map { (type, names) -> SyntheticClass("$pkg.R.$type", fields = names.map { SyntheticField(it, constant = "0x1") }) },
    )

    private val classes = listOf(
        r("com.example.app", "attr" to listOf("colorPrimary", "boxStrokeColor"), "string" to listOf("app_name")),
        r("androidx.appcompat", "attr" to listOf("colorPrimary")),
        r("com.google.android.material", "attr" to listOf("boxStrokeColor")),
    )

    private fun suggest(code: String, name: String, own: String? = "com.example.app"): List<LibraryRReference.Companion.Suggestion> {
        val from = code.lastIndexOf(name)
        return LibraryRReference.suggestions(code, from, from + name.length, own, classes)
    }

    private fun applied(code: String, s: LibraryRReference.Companion.Suggestion) = code.replaceRange(s.start, s.end, s.replacement)

    @Test
    fun `a resource read through the wrong library's R is pointed at the library that declares it, then the module's own`() {
        val code = "int c = com.google.android.material.R.attr.colorPrimary;"
        val fixes = suggest(code, "colorPrimary")
        assertEquals(2, fixes.size, fixes.map { it.title }.toString())
        assertTrue(fixes[0].title.startsWith("Use androidx.appcompat.R.attr.colorPrimary") && "not in material" in fixes[0].title, fixes[0].title)
        assertEquals("int c = androidx.appcompat.R.attr.colorPrimary;", applied(code, fixes[0]))
        assertEquals("int c = com.example.app.R.attr.colorPrimary;", applied(code, fixes[1]))
    }

    @Test
    fun `an imported library R, plain or aliased, is recognised and the whole expression replaced`() {
        val java = "import com.google.android.material.R;\nclass A { int c = R.attr.colorPrimary; }"
        assertEquals(
            "import com.google.android.material.R;\nclass A { int c = androidx.appcompat.R.attr.colorPrimary; }",
            applied(java, suggest(java, "colorPrimary").first()),
        )
        val kotlin = "import com.google.android.material.R as MR\nval c = MR.attr.colorPrimary"
        assertEquals(
            "import com.google.android.material.R as MR\nval c = androidx.appcompat.R.attr.colorPrimary",
            applied(kotlin, suggest(kotlin, "colorPrimary").first()),
        )
    }

    @Test
    fun `nothing is offered when the mistake is a different one`() {
        // The resource exists nowhere.
        assertTrue(suggest("x = com.google.android.material.R.attr.noSuchAttr;", "noSuchAttr").isEmpty())
        // Missing from the module's OWN R: a real missing resource, not a wrong package.
        assertTrue(suggest("x = com.example.app.R.string.missing;", "missing").isEmpty())
        // The module's own R by its simple name (no import of a library R).
        assertTrue(suggest("class A { int c = R.attr.colorPrimary; }", "colorPrimary").isEmpty())
        // The library does declare it: whatever is unresolved here, it is not this.
        assertTrue(suggest("x = com.google.android.material.R.attr.boxStrokeColor;", "boxStrokeColor").isEmpty())
        // Not an R reference at all, and not a resource type.
        assertTrue(suggest("x = something.colorPrimary;", "colorPrimary").isEmpty())
        assertTrue(suggest("x = com.google.android.material.R.things.colorPrimary;", "colorPrimary").isEmpty())
        // A class called R only by coincidence of its last letters.
        assertTrue(suggest("x = com.google.android.material.BR.attr.colorPrimary;", "colorPrimary").isEmpty())
    }

    @Test
    fun `a module with no R of its own still gets the declaring library`() {
        val fixes = suggest("x = com.google.android.material.R.attr.colorPrimary", "colorPrimary", own = null)
        assertEquals(listOf("androidx.appcompat.R.attr.colorPrimary", "com.example.app.R.attr.colorPrimary"), fixes.map { it.replacement })
    }
}
