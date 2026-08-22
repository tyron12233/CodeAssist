package dev.ide.core

import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiIconRef
import dev.ide.ui.backend.UiTextEdit
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Referencing an icon from the file you are editing. XML, Java and Kotlin each name the same drawable
 * differently, and Kotlin names it differently again inside a Compose file, so this asserts the text that
 * results in every one of those cases (edits are applied rather than inspected, because their order is an
 * implementation detail).
 */
class IconInsertionTest {

    private val drawable = UiIconRef.Resource("drawable", "ic_home")
    private val composeIcon = UiIconRef.ComposeIcon("ShoppingCart", "filled")

    private fun apply(text: String, edits: List<UiTextEdit>): String {
        var out = text
        for (edit in edits.sortedByDescending { it.start }) {
            out = out.replaceRange(edit.start, edit.end, edit.newText)
        }
        return out
    }

    /** Runs an insertion against the Android sample, with [fileName] deciding the language. */
    private fun insert(
        fileName: String,
        text: String,
        caret: Int = text.length,
        ref: UiIconRef = drawable,
        underModule: String = "app",
    ): String = withTempDir("icon-insert") { dir ->
        IdeServices.bootstrapDemo(dir).use { ide ->
            runBlocking {
                val path = sourceFile(dir, underModule, fileName)
                val edits = IdeServicesBackend(ide).icons.iconInsertion(path.toString(), text, caret, ref)
                apply(text, edits)
            }
        }
    }

    /** A path inside [module]'s main Kotlin/Java source root, so the file resolves to that module. */
    private fun sourceFile(dir: Path, module: String, fileName: String): Path =
        dir.resolve(module).resolve("src/main/java/com/example/app").resolve(fileName)

    // --- Kotlin ------------------------------------------------------------------------------------------

    @Test
    fun aComposeKotlinFileWrapsADrawableInAPainter() {
        val before = """
            package com.example.app.ui

            import androidx.compose.runtime.Composable

            @Composable
            fun Screen() {
            }
        """.trimIndent()
        val after = insert("Screen.kt", before, caret = before.indexOf("fun Screen() {") + "fun Screen() {".length)

        assertTrue(
            after.contains("Icon(painterResource(R.drawable.ic_home), contentDescription = null)"),
            after,
        )
        assertTrue(after.contains("import androidx.compose.ui.res.painterResource"), after)
        assertTrue(after.contains("import androidx.compose.material3.Icon"), after)
        assertTrue(after.contains("import com.example.app.R"), "the R class needs importing here: $after")
    }

    @Test
    fun aPlainKotlinFileJustNamesTheResource() {
        val before = "package com.example.app.data\n\nval icon = \n"
        val after = insert("Data.kt", before, caret = before.indexOf("val icon = ") + "val icon = ".length)

        assertTrue(after.contains("val icon = R.drawable.ic_home"), after)
        assertTrue(!after.contains("painterResource"), "no Compose wrapper outside a Compose file: $after")
        assertTrue(!after.contains("material3.Icon"), after)
        assertTrue(after.contains("import com.example.app.R"), after)
    }

    @Test
    fun aComposeIconInsertsItsPropertyAndBothOfItsImports() {
        val before = "package com.example.app.ui\n\nimport androidx.compose.runtime.Composable\n\nfun f() {}\n"
        val after = insert("Screen.kt", before, ref = composeIcon)

        assertTrue(after.contains("Icon(Icons.Filled.ShoppingCart, contentDescription = null)"), after)
        assertTrue(after.contains("import androidx.compose.material.icons.Icons"), after)
        assertTrue(after.contains("import androidx.compose.material.icons.filled.ShoppingCart"), after)
        assertTrue(!after.contains(".R\n"), "a Compose icon is not a resource, so no R import: $after")
    }

    @Test
    fun eachComposeStyleImportsItsOwnPackage() {
        for (style in listOf("filled", "outlined", "rounded", "sharp")) {
            val after = insert(
                "Screen.kt",
                "package a\n\nimport androidx.compose.runtime.Composable\n\nfun f() {}\n",
                ref = UiIconRef.ComposeIcon("Home", style),
            )
            val expected = style.replaceFirstChar { it.uppercaseChar() }
            assertTrue(after.contains("Icons.$expected.Home"), "$style: $after")
            assertTrue(after.contains("import androidx.compose.material.icons.$style.Home"), "$style: $after")
        }
    }

    // --- Java --------------------------------------------------------------------------------------------

    @Test
    fun javaNamesTheResourceAndTerminatesItsImportWithASemicolon() {
        val before = """
            package com.example.app.ui;

            import android.app.Activity;

            public class Main extends Activity {
                int icon = ;
            }
        """.trimIndent()
        val after = insert("Main.java", before, caret = before.indexOf("int icon = ") + "int icon = ".length)

        assertTrue(after.contains("int icon = R.drawable.ic_home;"), after)
        assertTrue(
            after.contains("import com.example.app.R;"),
            "a Java import keeps the semicolon the file already uses: $after",
        )
    }

    @Test
    fun aComposeIconHasNoJavaForm() {
        val after = insert("Main.java", "package a;\n\nclass Main {}\n", ref = composeIcon)
        assertEquals("package a;\n\nclass Main {}\n", after, "nothing is inserted")
    }

    // --- XML ---------------------------------------------------------------------------------------------

    @Test
    fun xmlInsertsTheWholeAttributeWhenTheCaretIsInATag() {
        val before = """
            <ImageView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="wrap_content" />
        """.trimIndent()
        val caret = before.indexOf("""android:layout_width""")
        val after = insert("view.xml", before, caret = caret)

        assertTrue(after.contains("""android:src="@drawable/ic_home""""), after)
    }

    @Test
    fun xmlInsertsOnlyTheValueWhenTheCaretIsBetweenTheQuotes() {
        val before = """<ImageView android:src="" />"""
        val caret = before.indexOf("\"\"") + 1
        val after = insert("view.xml", before, caret = caret)

        assertEquals("""<ImageView android:src="@drawable/ic_home" />""", after)
    }

    @Test
    fun xmlInElementContentStillInsertsTheAttributeForm() {
        // Between elements there is no attribute to fill, so the caller gets something they can move.
        val before = "<LinearLayout>\n    \n</LinearLayout>"
        val after = insert("view.xml", before, caret = before.indexOf("    \n") + 4)
        assertTrue(after.contains("""android:src="@drawable/ic_home""""), after)
    }

    @Test
    fun xmlNeedsNoImports() {
        val before = """<ImageView android:src="" />"""
        val after = insert("view.xml", before, caret = before.indexOf("\"\"") + 1)
        assertTrue(!after.contains("import"), after)
    }

    @Test
    fun aComposeIconHasNoXmlForm() {
        val before = """<ImageView android:src="" />"""
        assertEquals(before, insert("view.xml", before, caret = 24, ref = composeIcon))
    }

    // --- imports -----------------------------------------------------------------------------------------

    @Test
    fun theRClassIsNotImportedIntoItsOwnPackage() {
        // `R` is generated into the module's namespace, so a file already there refers to it unqualified.
        val before = "package com.example.app\n\nval icon = \n"
        val after = insert("Same.kt", before, caret = before.indexOf("val icon = ") + "val icon = ".length)

        assertTrue(after.contains("R.drawable.ic_home"), after)
        assertTrue(!after.contains("import com.example.app.R"), "a redundant import would be flagged: $after")
    }

    @Test
    fun anImportThatIsAlreadyThereIsNotDuplicated() {
        val before = """
            package com.example.app.ui

            import androidx.compose.material.icons.Icons
            import androidx.compose.material.icons.filled.ShoppingCart
            import androidx.compose.material3.Icon
            import androidx.compose.runtime.Composable

            fun f() {}
        """.trimIndent()
        val after = insert("Screen.kt", before, ref = composeIcon)

        for (fqn in listOf(
            "androidx.compose.material.icons.Icons",
            "androidx.compose.material.icons.filled.ShoppingCart",
            "androidx.compose.material3.Icon",
        )) {
            assertEquals(
                1,
                Regex("^import ${Regex.escape(fqn)}$", RegexOption.MULTILINE).findAll(after).count(),
                "$fqn was duplicated:\n$after",
            )
        }
    }

    @Test
    fun importsLandAfterTheExistingOnesAndBeforeAnyDeclaration() {
        val before = """
            package com.example.app.ui

            import androidx.compose.runtime.Composable

            fun f() {}
        """.trimIndent()
        val after = insert("Screen.kt", before, ref = composeIcon)
        val lines = after.lines()
        val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
        val firstDeclaration = lines.indexOfFirst { it.startsWith("fun ") }
        assertTrue(lastImport in 1 until firstDeclaration, "imports must precede declarations:\n$after")
    }

    @Test
    fun aFileWithNoPackageOrImportsStillGetsUsableText() {
        val after = insert("Loose.kt", "fun f() {}\n", caret = 0, ref = composeIcon)
        assertTrue(after.contains("import androidx.compose.material.icons.Icons"), after)
        assertTrue(after.contains("Icon(Icons.Filled.ShoppingCart"), after)
    }

    @Test
    fun aCaretPastTheEndIsClampedRatherThanCrashing() {
        val after = insert("Loose.kt", "package a\n", caret = 9_999)
        assertTrue(after.contains("R.drawable.ic_home"), after)
    }

    @Test
    fun anUnknownFileTypeFallsBackToTheResourceReference() {
        val after = insert("notes.txt", "hello ", caret = 6)
        assertEquals("hello @drawable/ic_home", after)
    }
}
