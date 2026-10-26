package dev.ide.lang.xml.formatting

import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.WrapPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reformat Code for XML: Android Studio's per-file-type layout over the real XML PSI. */
class XmlFormatterTest {

    private val studio = FormatStyle(styleId = "android_studio", indentSize = 4, tabWidth = 4, blankLinesToKeep = 2)

    private fun format(
        path: String,
        src: String,
        options: XmlFormatOptions = XmlFormatOptions(),
        style: FormatStyle = studio,
    ): String {
        val once = XmlFormatter.reformatText(path, src, style, options)
        // Every case is also an idempotency check, and (without rearranging) a whitespace-only check.
        assertEquals(once, XmlFormatter.reformatText(path, once, style, options), "formatting is not idempotent")
        if (!options.rearrangeAttributes) {
            assertEquals(src.filterNot { it.isWhitespace() }, once.filterNot { it.isWhitespace() }, "changed more than whitespace")
        }
        return once
    }

    @Test
    fun layoutPutsEachAttributeOnItsOwnLineWithABlankLineBeforeEveryView() {
        val src = """
            <?xml version="1.0" encoding="utf-8"?><LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"
            android:layout_width="match_parent"   android:layout_height = "match_parent" tools:context=".Main" >
            <TextView android:id="@+id/title" android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                    <Button android:text="Go"/>
            <include layout="@layout/footer" ></include>
            </LinearLayout >
        """.trimIndent()

        val expected = """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                tools:context=".Main">

                <TextView
                    android:id="@+id/title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />

                <Button android:text="Go" />

                <include layout="@layout/footer"></include>
            </LinearLayout>

        """.trimIndent()
        assertEquals(expected, format("app/src/main/res/layout/activity_main.xml", src))
    }

    @Test
    fun manifestSeparatesRunsOfDifferentTags() {
        val src = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.CAMERA"/>
            <application android:label="@string/app_name" android:theme="@style/Theme">
            <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            </activity>
            </application>
            </manifest>
        """.trimIndent()

        val expected = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.CAMERA" />

                <application
                    android:label="@string/app_name"
                    android:theme="@style/Theme">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />

                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>

        """.trimIndent()
        assertEquals(expected, format("app/src/main/AndroidManifest.xml", src))
    }

    @Test
    fun valuesKeepAttributesOnTheTagLineAndTextAsWritten() {
        val src = """
            <resources>
              <string name="app_name">My  App</string>
              <string name="rich">Hello <b>you</b> there</string>
                <style name="Theme.App"   parent="Theme.Material3.DayNight">
              <item name="colorPrimary">@color/purple</item>
                </style>
                <string name="multi">
                  keep
                     this
                </string>
            </resources>
        """.trimIndent()

        val expected = """
            <resources>
                <string name="app_name">My  App</string>
                <string name="rich">Hello <b>you</b> there</string>
                <style name="Theme.App" parent="Theme.Material3.DayNight">
                    <item name="colorPrimary">@color/purple</item>
                </style>
                <string name="multi">
                  keep
                     this
                </string>
            </resources>

        """.trimIndent()
        assertEquals(expected, format("app/src/main/res/values-night/strings.xml", src))
    }

    @Test
    fun valuesWrapOnlyWhenTheLineIsTooLong() {
        val narrow = studio.copy(maxLineLength = 60)
        val src = """<resources><style name="Theme.Application.Long" parent="Theme.Material3.DayNight.NoActionBar" /></resources>"""
        val expected = """
            <resources>
                <style name="Theme.Application.Long"
                    parent="Theme.Material3.DayNight.NoActionBar" />
            </resources>

        """.trimIndent()
        assertEquals(expected, format("res/values/themes.xml", src, style = narrow))
    }

    @Test
    fun drawablesWrapEveryAttributeWithoutBlankLines() {
        val src = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp">
            <path android:fillColor="#FF000000" android:pathData="M12,2L2,22h20z"/>
            <path android:pathData="M0,0h24v24H0z"/>
            </vector>
        """.trimIndent()

        val expected = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp"
                android:height="24dp">
                <path
                    android:fillColor="#FF000000"
                    android:pathData="M12,2L2,22h20z" />
                <path android:pathData="M0,0h24v24H0z" />
            </vector>

        """.trimIndent()
        assertEquals(expected, format("res/drawable-v24/ic_triangle.xml", src))
    }

    @Test
    fun commentsMoveWithTheirLinesAndKeepTheirInnerAlignment() {
        val src = """
            <menu xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- The settings entry.
                 Always last. -->
            <item android:id="@+id/settings" android:title="Settings"/> <!-- trailing -->
            </menu>
        """.trimIndent()

        val expected = """
            <menu xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- The settings entry.
                     Always last. -->
                <item
                    android:id="@+id/settings"
                    android:title="Settings" /> <!-- trailing -->
            </menu>

        """.trimIndent()
        assertEquals(expected, format("res/menu/main.xml", src))
    }

    @Test
    fun aLayoutCommentTakesTheBlankLineAboveTheViewItHeads() {
        val src = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <!-- header -->
            <View android:id="@+id/a"/>
            </FrameLayout>
        """.trimIndent()

        val expected = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">

                <!-- header -->
                <View android:id="@+id/a" />
            </FrameLayout>

        """.trimIndent()
        assertEquals(expected, format("res/layout/frame.xml", src))
    }

    @Test
    fun blankLinesCollapseToTheConfiguredMaximum() {
        val src = "<resources>\n\n\n\n\n    <bool name=\"a\">true</bool>\n\n\n\n    <bool name=\"b\">false</bool>\n</resources>\n"
        val expected = "<resources>\n\n\n    <bool name=\"a\">true</bool>\n\n\n    <bool name=\"b\">false</bool>\n</resources>\n"
        assertEquals(expected, format("res/values/bools.xml", src))
        val none = "<resources>\n    <bool name=\"a\">true</bool>\n    <bool name=\"b\">false</bool>\n</resources>\n"
        assertEquals(none, format("res/values/bools.xml", src, style = studio.copy(blankLinesToKeep = 0)))
    }

    @Test
    fun cdataAndPreservedSpaceAreLeftAlone() {
        val src = """
            <root>
            <script><![CDATA[  if (a < b)  { go() } ]]></script>
            <pre xml:space="preserve">
               <b/>   <i/>
            </pre>
            </root>
        """.trimIndent()

        val expected = """
            <root>
                <script><![CDATA[  if (a < b)  { go() } ]]></script>
                <pre xml:space="preserve">
               <b/>   <i/>
            </pre>
            </root>

        """.trimIndent()
        assertEquals(expected, format("config/settings.xml", src))
    }

    @Test
    fun genericXmlFollowsTheConfiguredWrapPolicy() {
        val src = "<config><entry key=\"a\" value=\"1\" type=\"int\"/></config>"
        val onePerLine = XmlFormatOptions(wrapAttributes = WrapPolicy.ONE_PER_LINE)
        val expected = """
            <config>
                <entry key="a"
                    value="1"
                    type="int" />
            </config>

        """.trimIndent()
        assertEquals(expected, format("config/app.xml", src, onePerLine))

        // With the Android conventions off, even a layout follows the policy.
        val plain = XmlFormatOptions(androidConventions = false, wrapAttributes = WrapPolicy.NEVER)
        val layout = "<LinearLayout android:a=\"1\" android:b=\"2\"><View android:c=\"3\" android:d=\"4\"/></LinearLayout>"
        val expectedLayout = """
            <LinearLayout android:a="1" android:b="2">
                <View android:c="3" android:d="4" />
            </LinearLayout>

        """.trimIndent()
        assertEquals(expectedLayout, format("res/layout/a.xml", layout, plain))
    }

    @Test
    fun honorsTabsAndTheEmptyTagSpace() {
        val src = "<resources><item name=\"a\"/><declare-styleable name=\"V\"><attr name=\"x\"/></declare-styleable></resources>"
        val tabs = studio.copy(useTabs = true)
        val expected = "<resources>\n\t<item name=\"a\"/>\n\t<declare-styleable name=\"V\">\n\t\t<attr name=\"x\"/>\n\t</declare-styleable>\n</resources>\n"
        assertEquals(expected, format("res/values/attrs.xml", src, XmlFormatOptions(spaceInEmptyTag = false), tabs))
    }

    @Test
    fun keepsWindowsLineEndings() {
        val src = "<resources>\r\n<color name=\"a\">#fff</color>\r\n</resources>"
        assertEquals(
            "<resources>\r\n    <color name=\"a\">#fff</color>\r\n</resources>\r\n",
            format("res/values/colors.xml", src),
        )
    }

    @Test
    fun rearrangesAttributesIntoAndroidStudioOrder() {
        val src = """
            <LinearLayout xmlns:tools="http://schemas.android.com/tools" android:orientation="vertical" xmlns:android="http://schemas.android.com/apk/res/android" tools:context=".Main" android:layout_height="match_parent" app:elevation="2dp" android:id="@+id/root" style="@style/Box" android:layout_marginTop="4dp" android:layout_width="match_parent"/>
        """.trimIndent()

        val expected = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                android:id="@+id/root"
                style="@style/Box"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:layout_marginTop="4dp"
                android:orientation="vertical"
                app:elevation="2dp"
                tools:context=".Main" />

        """.trimIndent()
        assertEquals(expected, format("res/layout/a.xml", src, XmlFormatOptions(rearrangeAttributes = true)))
    }

    @Test
    fun aSelectionReformatsOnlyTheLinesItTouches() {
        val src = "<resources>\n<color name=\"a\">#fff</color>\n<color name=\"b\">#000</color>\n</resources>\n"
        val line = src.indexOf("<color name=\"b\"")
        val edits = XmlFormatter.reformat("res/values/colors.xml", src, studio, XmlFormatOptions(), line + 3, line + 5)
        val sb = StringBuilder(src)
        for (e in edits.asReversed()) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
        assertEquals("<resources>\n<color name=\"a\">#fff</color>\n    <color name=\"b\">#000</color>\n</resources>\n", sb.toString())
    }

    @Test
    fun brokenMarkupIsNeverDamaged() {
        // An unclosed tag and a broken attribute: the formatter may give up on parts, but never on content.
        val inputs = listOf(
            "<LinearLayout>\n<TextView android:text=\"hi\"/>\n",
            "<LinearLayout>\n  <TextView android:text=\"hi />\n</LinearLayout>",
            "<a><b></a>",
            "text before <root/>",
            "<resources>\n<string name=\"x\">a &amp b</string>\n</resources>",
        )
        for (src in inputs) {
            val out = XmlFormatter.reformatText("res/layout/x.xml", src, studio)
            assertEquals(src.filterNot { it.isWhitespace() }, out.filterNot { it.isWhitespace() }, "damaged: $src")
        }
    }

    @Test
    fun alreadyFormattedProducesNoEdits() {
        val src = """
            <?xml version="1.0" encoding="utf-8"?>
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content" />

            </FrameLayout>

        """.trimIndent()
        val edits = XmlFormatter.reformat("res/layout/main.xml", src, studio)
        assertTrue(edits.isEmpty(), "expected no edits, got $edits")
    }
}
