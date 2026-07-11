package dev.ide.lang.xml.folding

import dev.ide.lang.folding.FoldKind
import dev.ide.lang.xml.PsiXmlProjection
import kotlin.test.Test
import kotlin.test.assertTrue

/** Folding is computed on the real PSI (exact XmlTagValue / XmlComment ranges). */
class XmlFoldingServiceTest {

    @Test
    fun foldsElementBodiesAndComments() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- a
                     multi-line comment -->
                <TextView android:text="hi" />
            </LinearLayout>
        """.trimIndent()

        val folds = PsiXmlProjection.folds("res/layout/screen.xml", xml)
        assertTrue(folds.any { it.kind == FoldKind.BLOCK }, "expected an element-body fold; got $folds")
        assertTrue(folds.any { it.kind == FoldKind.COMMENT }, "expected a comment fold; got $folds")
        // The LinearLayout body fold spans between its '>' and '</LinearLayout>'.
        val body = folds.first { it.kind == FoldKind.BLOCK }
        assertTrue(body.range.start > 0 && body.range.end <= xml.length && body.range.start < body.range.end)
    }
}
