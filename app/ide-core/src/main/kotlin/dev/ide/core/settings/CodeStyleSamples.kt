package dev.ide.core.settings

/**
 * Fixed, deliberately-messy code samples the Code Style screen formats live to preview a profile. Each one
 * exercises the visible rules: nesting/indent, a long call (wrapping), an `if` block (braces), a method and
 * field (blank lines), operators/commas (spacing), and a doc comment.
 */
object CodeStyleSamples {

    fun forLanguage(languageId: String): String = when (languageId) {
        CodeStyleSettings.LANG_KOTLIN -> KOTLIN
        CodeStyleSettings.LANG_XML -> XML
        else -> JAVA
    }

    private val JAVA = """
        package sample;
        import java.util.List;
        /** A greeter. */
        public class Sample {
        private final String name;
        public Sample(String name){this.name=name;}
        public String greet(List<String> who, int times){
        String out = "";
        for(int i=0;i<times;i++){
        out = out + render(name, who.get(0), i) + ", " + render(name, who.get(1), i);
        }
        return out;
        }
        }
    """.trimIndent()

    private val KOTLIN = """
        package sample
        import kotlin.math.max
        /** A greeter. */
        class Sample(val name: String) {
        fun greet(who: List<String>, times: Int): String {
        var out = ""
        for (i in 0 until times) {
        out = out + render(name, who[0], i) + ", " + render(name, who[1], i)
        }
        return out
        }
        }
    """.trimIndent()

    // Formatted as a layout: nesting, attribute wrapping, empty-tag spacing, a comment, and blank lines.
    private val XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical" android:layout_width="match_parent" android:layout_height="match_parent">
        <!-- Header -->
        <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="@string/app_name"/>



        <Button android:id="@+id/go" android:text="Go"
        android:layout_width="wrap_content" android:layout_height="wrap_content"/>
        <View android:layout_height="1dp"/>
        </LinearLayout>
    """.trimIndent()
}
