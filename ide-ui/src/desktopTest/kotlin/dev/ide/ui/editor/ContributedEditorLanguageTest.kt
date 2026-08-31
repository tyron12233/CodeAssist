package dev.ide.ui.editor

import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.core.TokenType
import dev.ide.ui.editor.core.newlineHandlerFor
import dev.ide.ui.editor.core.styleLine
import dev.ide.ui.ext.EditorLanguageProfile
import dev.ide.ui.ext.EditorLanguageRegistry
import dev.ide.ui.ext.SyntaxFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A language the shell does not ship must be colored, commented and indented purely by registering an
 * [EditorLanguageProfile]. Before the profile registry these were a closed `when` over an enum, so a plugin
 * could contribute a full language backend and its files still opened as uncolored plain text.
 */
class ContributedEditorLanguageTest {

    private fun myLang() = EditorLanguageProfile(
        id = "mylang",
        suffixes = listOf(".mylang"),
        syntax = SyntaxFamily.C_FAMILY,
        keywords = setOf("rule", "given", "then"),
        lineComment = "//",
        blockCommentOpen = "/*",
        blockCommentClose = "*/",
    )

    private fun <T> withProfile(profile: EditorLanguageProfile, block: () -> T): T {
        val reg = EditorLanguageRegistry.register(profile)
        try {
            return block()
        } finally {
            reg.dispose()
        }
    }

    @Test
    fun `a contributed suffix resolves to its own language, not to Plain`() {
        withProfile(myLang()) {
            val language = languageFor("policy.mylang")
            assertEquals("mylang", language.id)
            assertEquals(SyntaxFamily.C_FAMILY, language.syntax)
        }
    }

    @Test
    fun `an unregistered suffix is still Plain`() {
        assertEquals(CodeLanguage.Plain, languageFor("notes.unknownext"))
    }

    @Test
    fun `the contributed keywords are colored by the shared C-family scanner`() {
        withProfile(myLang()) {
            val language = languageFor("policy.mylang")
            val styled = styleLine("rule Foo { }", entryState = 0, language = language)
            val keywordSpans = styled.spans.filter { it.type == TokenType.KEYWORD }
            assertEquals(1, keywordSpans.size, "exactly `rule` is a keyword here")
            assertEquals(0, keywordSpans[0].start)
            assertEquals(4, keywordSpans[0].end)
        }
    }

    @Test
    fun `a word that is not in the profile's keyword set is not colored as one`() {
        withProfile(myLang()) {
            val language = languageFor("policy.mylang")
            // `class` is a Java keyword but not one of this language's, so the profile must win.
            val styled = styleLine("class Foo", entryState = 0, language = language)
            assertTrue(styled.spans.none { it.type == TokenType.KEYWORD })
        }
    }

    @Test
    fun `toggle comment uses the profile's line comment`() {
        withProfile(myLang()) {
            val session = EditorSession("rule Foo", languageFor("policy.mylang"))
            session.toggleComment()
            assertEquals("// rule Foo", session.doc.text)
            session.toggleComment()
            assertEquals("rule Foo", session.doc.text)
        }
    }

    /** A brace language must get the smart indent, i.e. Enter inside `{` indents rather than staying flush. */
    @Test
    fun `a C-family profile gets the brace-language Enter handler`() {
        withProfile(myLang()) {
            val contributed = newlineHandlerFor(languageFor("policy.mylang"))
            val builtIn = newlineHandlerFor(CodeLanguage.Java)
            val source = "rule Foo {"
            assertEquals(
                builtIn.onEnter(source, source.length).text,
                contributed.onEnter(source, source.length).text,
                "a contributed C-family language must indent exactly like the built-in brace languages",
            )
        }
    }

    @Test
    fun `the built-in languages keep resolving to their shared instances`() {
        assertSame(CodeLanguage.Java, languageFor("Main.java"))
        assertSame(CodeLanguage.Kotlin, languageFor("Main.kt"))
        assertSame(CodeLanguage.Kotlin, languageFor("build.gradle.kts"))
        assertSame(CodeLanguage.Xml, languageFor("layout.xml"))
        assertSame(CodeLanguage.Aidl, languageFor("IFoo.aidl"))
        assertSame(CodeLanguage.Proguard, languageFor("proguard-rules.pro"))
        assertSame(CodeLanguage.Markdown, languageFor("README.md"))
    }

    @Test
    fun `a lower-order profile overrides a built-in suffix`() {
        val override = EditorLanguageProfile(
            id = "mylang-java", suffixes = listOf(".java"), syntax = SyntaxFamily.C_FAMILY,
            keywords = setOf("gadget"), order = 0,
        )
        withProfile(override) {
            assertEquals("mylang-java", languageFor("Main.java").id)
        }
        // …and the built-in is back once the contribution is disposed.
        assertSame(CodeLanguage.Java, languageFor("Main.java"))
    }
}
