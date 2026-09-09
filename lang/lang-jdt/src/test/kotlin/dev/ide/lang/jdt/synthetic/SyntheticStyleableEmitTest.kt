package dev.ide.lang.jdt.synthetic

import dev.ide.lang.synthetic.SyntheticClass
import dev.ide.lang.synthetic.SyntheticField
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the `R.styleable` emit shape the layout-preview's custom-view path depends on: an `int[]` array
 * field with a brace initializer, plus the `int` per-attr index constants. User code's
 * `obtainStyledAttributes(attrs, R.styleable.MyChart, …)` only compiles if this is valid Java.
 */
class SyntheticStyleableEmitTest {

    @Test fun `styleable int array and index constants emit valid java`() {
        val cls = SyntheticClass(
            fqName = "com.example.app.R.styleable",
            fields = listOf(
                SyntheticField("MyChart", type = "int[]", constant = "{ 0x7f020000, 0x7f020001 }"),
                SyntheticField("MyChart_barColor", constant = "0"),
                SyntheticField("MyChart_maxValue", constant = "1"),
            ),
        )
        val java = SyntheticJavaSource.emit(cls)
        assertTrue("public static final int[] MyChart = { 0x7f020000, 0x7f020001 };" in java, java)
        assertTrue("public static final int MyChart_barColor = 0;" in java, java)
        assertTrue("public static final int MyChart_maxValue = 1;" in java, java)
    }

    @Test fun `regular R fields carry their stable hex id constant`() {
        val cls = SyntheticClass(
            fqName = "com.example.app.R.color",
            fields = listOf(SyntheticField("primary", constant = "0x7f010000")),
        )
        assertTrue("public static final int primary = 0x7f010000;" in SyntheticJavaSource.emit(cls))
    }
}
