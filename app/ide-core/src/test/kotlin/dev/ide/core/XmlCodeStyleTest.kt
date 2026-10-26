package dev.ide.core

import dev.ide.core.settings.CodeStyleSamples
import dev.ide.core.settings.CodeStyleSettings
import dev.ide.core.settings.SettingsStore
import dev.ide.lang.formatting.WrapPolicy
import dev.ide.lang.xml.formatting.XmlFormatOptions
import dev.ide.lang.xml.formatting.XmlFormatter
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The XML Code Style profile: its default, persistence, and what the preview sample formats to. */
class XmlCodeStyleTest {

    private fun store(): SettingsStore {
        val prefs = HashMap<String, String>()
        return SettingsStore(get = { prefs[it] }, set = { k, v -> prefs[k] = v })
    }

    @Test
    fun xmlFilesUseTheXmlProfile() {
        assertEquals(CodeStyleSettings.LANG_XML, CodeStyleSettings.languageForPath("/p/app/src/main/res/layout/a.xml"))
        assertEquals(CodeStyleSettings.LANG_KOTLIN, CodeStyleSettings.languageForPath("/p/A.kt"))
        assertEquals(CodeStyleSettings.LANG_JAVA, CodeStyleSettings.languageForPath("/p/A.java"))
    }

    @Test
    fun defaultsToAndroidStudio() {
        val profile = store().loadCodeStyle(CodeStyleSettings.LANG_XML)
        assertEquals(CodeStyleSettings.PRESET_ANDROID_STUDIO, profile.preset)
        val style = profile.toFormatStyle()
        assertEquals(4, style.indentSize)
        assertEquals(2, style.blankLinesToKeep)
        assertEquals(XmlFormatOptions(), profile.toXmlFormatOptions())
    }

    @Test
    fun customXmlOptionsSurviveASaveAndLoad() {
        val s = store()
        val custom = CodeStyleSettings.default(CodeStyleSettings.LANG_XML).copy(
            preset = CodeStyleSettings.PRESET_CUSTOM,
            indentSize = 2,
            xmlAndroidConventions = false,
            xmlWrapAttributes = CodeStyleSettings.WRAP_ONE_PER_LINE,
            xmlSpaceInEmptyTag = false,
            xmlRearrangeAttributes = true,
        )
        s.saveCodeStyle(CodeStyleSettings.LANG_XML, custom)
        val loaded = s.loadCodeStyle(CodeStyleSettings.LANG_XML)
        assertEquals(custom, loaded)
        assertEquals(
            XmlFormatOptions(
                androidConventions = false,
                wrapAttributes = WrapPolicy.ONE_PER_LINE,
                spaceInEmptyTag = false,
                rearrangeAttributes = true,
            ),
            loaded.toXmlFormatOptions(),
        )
        assertEquals(2, loaded.toFormatStyle().indentSize)
    }

    @Test
    fun previewSampleFormatsAsALayout() {
        val profile = CodeStyleSettings.default(CodeStyleSettings.LANG_XML)
        val sample = CodeStyleSamples.forLanguage(CodeStyleSettings.LANG_XML)
        val out = XmlFormatter.reformatText("res/layout/preview.xml", sample, profile.toFormatStyle(), profile.toXmlFormatOptions())
        assertTrue(out != sample, "the sample should need formatting")
        assertTrue("\n    <TextView\n        android:id=\"@+id/title\"\n" in out, out)
        assertTrue("\n\n    <!-- Header -->\n    <TextView" in out, out)
        assertTrue("<View android:layout_height=\"1dp\" />" in out, out)
    }

    @Test
    fun reformatCodeReachesTheXmlFormatterThroughTheEngine() {
        val root = createTempDirectory("xml-format")
        val services = IdeServices.bootstrapDemo(root)
        try {
            val layout = root.resolve("app/src/main/res/layout/activity_main.xml")
            val messy = Files.readString(layout).replace("\n", "\n   \t ")
            val profile = CodeStyleSettings.default(CodeStyleSettings.LANG_XML)
            val edits = services.formatDocument(layout, messy, profile.toFormatStyle(), profile.toXmlFormatOptions())
            assertTrue(edits.isNotEmpty(), "expected the messy layout to be reformatted")
            val sb = StringBuilder(messy)
            for (e in edits.sortedByDescending { it.offset }) sb.replace(e.offset, e.offset + e.oldLength, e.newText.toString())
            val formatted = sb.toString()
            assertEquals(formatted, XmlFormatter.reformatText(layout.toString(), messy, profile.toFormatStyle()))
            assertTrue(services.formatDocument(layout, formatted, profile.toFormatStyle(), profile.toXmlFormatOptions()).isEmpty())
        } finally {
            services.close()
            root.toFile().deleteRecursively()
        }
    }
}
