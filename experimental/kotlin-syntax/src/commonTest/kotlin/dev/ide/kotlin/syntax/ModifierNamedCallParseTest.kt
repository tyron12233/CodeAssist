package dev.ide.kotlin.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Names that are also soft modifier keywords (`error`, `value`, `inline`, `private`, `context`), used where
 * the grammar has just finished a declaration and is deciding whether a modifier list starts next.
 *
 * Both cases are upstream parser fixes backported into the vendored grammar (see VENDOR.md): KT-90707 for an
 * enum entry with arguments or a body, KT-90782 for a call on the line after a local class. Before them the
 * editor reported a syntax error on code the compiler accepts, so every test here asserts there is no error
 * element as well as the shape that replaced it.
 */
class ModifierNamedCallParseTest {

    private fun tree(text: String) = KotlinSyntax.parse(text).render()

    private fun assertNoErrors(rendered: String) =
        assertFalse("ERROR_ELEMENT" in rendered, "unexpected syntax error in $rendered")

    private fun count(rendered: String, type: String) = Regex("\\($type\\b").findAll(rendered).count()

    @Test
    fun enumEntriesNamedLikeModifiersTakeArguments() {
        val rendered = tree(
            """
            enum class E(val code: String) {
                correct("correct"),
                error("error"),
                value("value"),
                inline("inline"),
                ;
            }
            """.trimIndent()
        )
        assertNoErrors(rendered)
        assertEquals(4, count(rendered, "ENUM_ENTRY"))
        assertEquals(4, count(rendered, "INITIALIZER_LIST"))
        // The name is the entry's own identifier, not swallowed into a modifier list.
        assertFalse("MODIFIER_LIST" in rendered.substringAfter("(CLASS_BODY"), rendered)
    }

    @Test
    fun enumEntriesNamedLikeModifiersTakeBodies() {
        val rendered = tree(
            """
            enum class E3 {
                error {},
                value {},
                inline {},
                ;
            }

            enum class E4(val code: String) {
                error("error") {},
                value("value") {},
                inline("inline") {},
                ;
            }
            """.trimIndent()
        )
        assertNoErrors(rendered)
        assertEquals(6, count(rendered, "ENUM_ENTRY"))
        assertEquals(3, count(rendered, "INITIALIZER_LIST"))
    }

    @Test
    fun aModifierOnAnEnumEntryIsStillAModifier() {
        // The follow set only stops a NAME being read as a modifier; a real modifier before the name stays one.
        val rendered = tree("enum class E { @Deprecated(\"\") A, inline B }")
        assertEquals(2, count(rendered, "ENUM_ENTRY"))
        // One per entry, after the class's own `enum`.
        assertEquals(2, count(rendered.substringAfter("(CLASS_BODY"), "MODIFIER_LIST"), rendered)
    }

    @Test
    fun aCallNamedLikeAModifierAfterALocalClassIsAStatement() {
        val source = """
            fun f(): Int {
                class C
                error("boom")
            }

            fun g() {
                class C<T>
                inline("boom")
                abstract class D
                value {}
                data class E(val x: Int)
                private("boom")
                class F
                context("boom")
                class G
                private("boom") {}
            }

            fun h() {
                run {
                    class C
                    inline("boom")
                }
            }
        """.trimIndent()
        val rendered = tree(source)
        assertNoErrors(rendered)
        assertEquals(7, count(rendered, "CLASS"))
        // Only `E(val x: Int)` has one; no call was taken for a constructor.
        assertEquals(1, count(rendered, "PRIMARY_CONSTRUCTOR"), rendered)
        // `run`, plus one call after each local class.
        assertEquals(8, count(rendered, "CALL_EXPRESSION"), rendered)
    }

    @Test
    fun aLazilyParsedBodyGivesTheSameStatements() {
        // The editor parses bodies on demand through the block parser; it must take the local-declaration path too.
        val rendered = KotlinSyntax.parseBlock("{\n    class C\n    error(\"boom\")\n}").render()
        assertNoErrors(rendered)
        assertEquals(1, count(rendered, "CLASS"))
        assertEquals(1, count(rendered, "CALL_EXPRESSION"))
    }

    @Test
    fun aConstructorOnTheNextLineStillBelongsToTheLocalClass() {
        val rendered = tree(
            """
            annotation class Ann

            fun f() {
                class C1
                private constructor()

                class C2
                @Ann constructor()

                class C3<T>
                internal constructor(x: T)
            }
            """.trimIndent()
        )
        assertNoErrors(rendered)
        assertEquals(3, count(rendered, "PRIMARY_CONSTRUCTOR"))
        assertEquals(0, count(rendered, "CALL_EXPRESSION"))
    }

    @Test
    fun membersAndScriptsKeepTheirOwnRules() {
        // Outside a body nothing changed: a top-level class still takes a primary constructor's modifiers from
        // the next line.
        val topLevel = tree("class C\nprivate constructor()")
        assertNoErrors(topLevel)
        assertEquals(1, count(topLevel, "PRIMARY_CONSTRUCTOR"))

        // A script's top level is statements, so it gets the local rule.
        val script = KotlinSyntax.parse("class C\nerror(\"boom\")", isScript = true).render()
        assertNoErrors(script)
        assertTrue(count(script, "CALL_EXPRESSION") == 1, script)
    }
}
