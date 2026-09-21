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

    /**
     * A C-family language with a preprocessor. Without [EditorLanguageProfile.directivePrefix] the whole of
     * `#include <stdio.h>` scans as operators and an identifier, which is what a C or C++ plugin would have
     * shipped: the line that opens almost every source file, uncolored.
     */
    private fun cpp() = EditorLanguageProfile(
        id = "cpp",
        suffixes = listOf(".cpp", ".h"),
        syntax = SyntaxFamily.C_FAMILY,
        keywords = setOf("int", "return", "const"),
        lineComment = "//",
        directivePrefix = "#",
    )

    @Test
    fun `a preprocessor directive colors as a keyword and its angle header as a string`() {
        withProfile(cpp()) {
            val styled = styleLine("#include <stdio.h>", entryState = 0, language = languageFor("a.cpp"))
            val keyword = styled.spans.single { it.type == TokenType.KEYWORD }
            assertEquals(0, keyword.start)
            assertEquals("#include".length, keyword.end, "the marker and the directive word are one keyword")
            val header = styled.spans.single { it.type == TokenType.STRING }
            assertEquals("#include ".length, header.start)
            assertEquals("#include <stdio.h>".length, header.end, "the closing angle is part of the header")
        }
    }

    @Test
    fun `a directive still colors when the line is indented`() {
        withProfile(cpp()) {
            val styled = styleLine("  #define MAX 10", entryState = 0, language = languageFor("a.cpp"))
            val keyword = styled.spans.single { it.type == TokenType.KEYWORD }
            assertEquals(2, keyword.start)
            assertEquals("  #define".length, keyword.end)
            // The macro body is ordinary code, so the literal still colors as a number.
            assertTrue(styled.spans.any { it.type == TokenType.NUMBER })
        }
    }

    @Test
    fun `a quoted include is left to the ordinary string scanner`() {
        withProfile(cpp()) {
            val styled = styleLine("""#include "local.h"""", entryState = 0, language = languageFor("a.cpp"))
            val header = styled.spans.single { it.type == TokenType.STRING }
            assertEquals("""#include """.length, header.start)
        }
    }

    @Test
    fun `an unclosed angle header colors to end of line rather than flickering`() {
        withProfile(cpp()) {
            val styled = styleLine("#include <std", entryState = 0, language = languageFor("a.cpp"))
            val header = styled.spans.single { it.type == TokenType.STRING }
            assertEquals("#include <std".length, header.end)
        }
    }

    @Test
    fun `a hash inside code is not read as a directive`() {
        withProfile(cpp()) {
            // Only the head of the line can start a directive; `#` here is the stringize operator.
            val styled = styleLine("const int x = a # b;", entryState = 0, language = languageFor("a.cpp"))
            // `const` is a modifier and `int` is not, so they land in different keyword classes — both
            // still keywords, and a contributed language gets that split from the shared table.
            val keywords = styled.spans.filter {
                it.type == TokenType.KEYWORD || it.type == TokenType.KEYWORD_MODIFIER
            }
            assertEquals(setOf("const", "int"), keywords.mapTo(HashSet()) { "const int x = a # b;".substring(it.start, it.end) })
        }
    }

    @Test
    fun `a language with no directive prefix is unaffected`() {
        withProfile(myLang()) {
            val styled = styleLine("#include <stdio.h>", entryState = 0, language = languageFor("a.mylang"))
            assertTrue(styled.spans.none { it.type == TokenType.KEYWORD || it.type == TokenType.STRING })
        }
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
