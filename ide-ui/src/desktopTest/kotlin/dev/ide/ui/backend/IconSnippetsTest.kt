package dev.ide.ui.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two heuristics that decide *how* an icon reference is written, and the language mapping around them.
 * Shared by the backend (which builds the edits) and the picker (which labels its button), so they are worth
 * pinning down on their own.
 */
class IconSnippetsTest {

    private val drawable = UiIconRef.Resource("drawable", "ic_home")

    @Test
    fun languageComesFromTheExtension() {
        assertEquals(IconSnippets.KOTLIN, IconSnippets.languageOf("a/b/Main.kt"))
        assertEquals(IconSnippets.KOTLIN, IconSnippets.languageOf("build.gradle.kts"))
        assertEquals(IconSnippets.JAVA, IconSnippets.languageOf("Main.java"))
        assertEquals(IconSnippets.XML, IconSnippets.languageOf("res/layout/main.xml"))
        assertEquals(IconSnippets.OTHER, IconSnippets.languageOf("notes.txt"))
        assertEquals(IconSnippets.OTHER, IconSnippets.languageOf("Makefile"))
    }

    @Test
    fun aComposeIconIsKotlinOnly() {
        val icon = UiIconRef.ComposeIcon("Home")
        assertTrue(IconSnippets.supports(icon, IconSnippets.KOTLIN))
        assertFalse(IconSnippets.supports(icon, IconSnippets.JAVA))
        assertFalse(IconSnippets.supports(icon, IconSnippets.XML))
        assertNull(IconSnippets.snippet(icon, UiInsertionTarget("a.xml")))
    }

    @Test
    fun aResourceWorksEverywhere() {
        for (language in listOf(IconSnippets.KOTLIN, IconSnippets.JAVA, IconSnippets.XML, IconSnippets.OTHER)) {
            assertTrue(IconSnippets.supports(drawable, language), language)
        }
    }

    @Test
    fun styleNamesMatchTheLibrarysObjects() {
        assertEquals("Filled", IconSnippets.styleName("filled"))
        assertEquals("Outlined", IconSnippets.styleName("outlined"))
        assertEquals("Rounded", IconSnippets.styleName("rounded"))
        assertEquals("Sharp", IconSnippets.styleName("sharp"))
        assertEquals("TwoTone", IconSnippets.styleName("twotone"))
        assertEquals("Filled", IconSnippets.styleName("nonsense"), "the default family is the fallback")
    }

    @Test
    fun theComposeContextDecidesBetweenAPainterAndAPlainReference() {
        assertEquals(
            "Icon(painterResource(R.drawable.ic_home), contentDescription = null)",
            IconSnippets.snippet(drawable, UiInsertionTarget("A.kt", composeContext = true)),
        )
        assertEquals("R.drawable.ic_home", IconSnippets.snippet(drawable, UiInsertionTarget("A.kt")))
    }

    @Test
    fun onlyAComposeKotlinFileNeedsComposeImports() {
        assertEquals(
            listOf("androidx.compose.material3.Icon", "androidx.compose.ui.res.painterResource"),
            IconSnippets.imports(drawable, UiInsertionTarget("A.kt", composeContext = true)),
        )
        assertTrue(IconSnippets.imports(drawable, UiInsertionTarget("A.kt")).isEmpty())
        assertTrue(IconSnippets.imports(drawable, UiInsertionTarget("A.java")).isEmpty())
        assertTrue(IconSnippets.imports(drawable, UiInsertionTarget("a.xml")).isEmpty())
    }

    @Test
    fun onlyKotlinAndJavaNameTheRClass() {
        assertTrue(IconSnippets.needsRClass(drawable, UiInsertionTarget("A.kt")))
        assertTrue(IconSnippets.needsRClass(drawable, UiInsertionTarget("A.java")))
        assertFalse(IconSnippets.needsRClass(drawable, UiInsertionTarget("a.xml")))
        assertFalse(IconSnippets.needsRClass(UiIconRef.ComposeIcon("Home"), UiInsertionTarget("A.kt")))
    }

    // --- the Compose-context heuristic -------------------------------------------------------------------

    @Test
    fun aComposeImportOrAnnotationMarksAComposeFile() {
        assertTrue(IconSnippets.looksLikeCompose("import androidx.compose.runtime.Composable"))
        assertTrue(IconSnippets.looksLikeCompose("@Composable\nfun A() {}"))
        // An import is the reliable marker: a file can use Compose without declaring a composable itself.
        assertTrue(IconSnippets.looksLikeCompose("import androidx.compose.material3.Text\n\nval x = 1"))
        assertFalse(IconSnippets.looksLikeCompose("package a\n\nclass Plain"))
        assertFalse(IconSnippets.looksLikeCompose(""))
    }

    // --- the XML attribute heuristic ---------------------------------------------------------------------

    @Test
    fun theCaretBetweenAttributeQuotesIsInsideAValue() {
        val text = """<ImageView android:src="" />"""
        val caret = text.indexOf("\"\"") + 1
        assertTrue(IconSnippets.insideXmlAttributeValue(text, caret))
    }

    @Test
    fun theCaretElsewhereInATagIsNotInsideAValue() {
        val text = """<ImageView android:src="@drawable/x" android:tint="#fff" />"""
        // Right after a closed attribute pair the quote count is even, so the caret is between attributes.
        val caret = text.indexOf(" android:tint")
        assertFalse(IconSnippets.insideXmlAttributeValue(text, caret))
    }

    @Test
    fun theCaretInElementContentIsNotInsideAValue() {
        val text = "<LinearLayout>\n    \n</LinearLayout>"
        assertFalse(
            IconSnippets.insideXmlAttributeValue(text, text.indexOf("    ") + 4),
            "past the tag's `>` the caret is in content, not a tag",
        )
    }

    @Test
    fun aSingleQuotedAttributeCountsToo() {
        val text = "<ImageView android:src='' />"
        assertTrue(IconSnippets.insideXmlAttributeValue(text, text.indexOf("''") + 1))
    }

    @Test
    fun textWithNoTagAtAllIsNotInsideAValue() {
        assertFalse(IconSnippets.insideXmlAttributeValue("plain text", 5))
        assertFalse(IconSnippets.insideXmlAttributeValue("", 0))
    }

    @Test
    fun anOutOfRangeCaretIsClampedRatherThanThrowing() {
        val text = """<ImageView android:src="" />"""
        IconSnippets.insideXmlAttributeValue(text, -5)
        IconSnippets.insideXmlAttributeValue(text, 9_999)
    }

    // --- the reference form ------------------------------------------------------------------------------

    @Test
    fun theReferenceIsTheShortFormPerLanguage() {
        assertEquals("R.drawable.ic_home", IconSnippets.reference(drawable, IconSnippets.KOTLIN))
        assertEquals("R.drawable.ic_home", IconSnippets.reference(drawable, IconSnippets.JAVA))
        assertEquals("@drawable/ic_home", IconSnippets.reference(drawable, IconSnippets.XML))
        assertEquals(
            "Icons.Rounded.Home",
            IconSnippets.reference(UiIconRef.ComposeIcon("Home", "rounded"), IconSnippets.KOTLIN),
        )
    }

    @Test
    fun aMipmapKeepsItsOwnFolderInTheReference() {
        val mipmap = UiIconRef.Resource("mipmap", "ic_launcher")
        assertEquals("@mipmap/ic_launcher", IconSnippets.reference(mipmap, IconSnippets.XML))
        assertEquals("R.mipmap.ic_launcher", IconSnippets.reference(mipmap, IconSnippets.KOTLIN))
    }
}
