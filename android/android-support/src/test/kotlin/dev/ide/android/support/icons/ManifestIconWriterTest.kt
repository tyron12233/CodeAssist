package dev.ide.android.support.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pointing `<application>` at a generated launcher icon: replacing an existing reference, adding a missing
 * one, leaving an already-correct manifest alone, and not being fooled by lookalike text.
 */
class ManifestIconWriterTest {

    private val edit = ManifestIconEdit("@mipmap/ic_launcher", "@mipmap/ic_launcher_round")

    private fun manifest(applicationAttrs: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application$applicationAttrs>
                <activity android:name=".MainActivity"/>
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun anExistingIconReferenceIsReplacedInPlace() {
        val before = manifest("""
                android:icon="@mipmap/old_icon"
                android:roundIcon="@mipmap/old_icon_round"
                android:label="@string/app_name"""")
        val after = assertNotNull(ManifestIconWriter.apply(before, edit))

        assertTrue(after.contains("""android:icon="@mipmap/ic_launcher""""), after)
        assertTrue(after.contains("""android:roundIcon="@mipmap/ic_launcher_round""""), after)
        assertTrue(after.contains("""android:label="@string/app_name""""), "unrelated attributes survive")
        assertTrue(after.contains(".MainActivity"), "the rest of the document survives")
        assertTrue(!after.contains("old_icon"), after)
    }

    @Test
    fun missingAttributesAreInserted() {
        val after = assertNotNull(ManifestIconWriter.apply(manifest(" android:label=\"@string/x\""), edit))
        assertTrue(after.contains("""android:icon="@mipmap/ic_launcher""""), after)
        assertTrue(after.contains("""android:roundIcon="@mipmap/ic_launcher_round""""), after)
        assertTrue(after.contains("""android:label="@string/x""""), after)
    }

    @Test
    fun anAlreadyCorrectManifestIsLeftAlone() {
        val text = manifest("""
                android:icon="@mipmap/ic_launcher"
                android:roundIcon="@mipmap/ic_launcher_round"""")
        assertNull(ManifestIconWriter.apply(text, edit), "no edit means no needless file write")
    }

    @Test
    fun onlyTheAttributeThatDiffersIsChanged() {
        val text = manifest("""
                android:icon="@mipmap/ic_launcher"
                android:roundIcon="@mipmap/stale"""")
        val after = assertNotNull(ManifestIconWriter.apply(text, edit))
        assertEquals(1, Regex("""android:icon=""").findAll(after).count())
        assertTrue(after.contains("""android:roundIcon="@mipmap/ic_launcher_round""""), after)
    }

    @Test
    fun aRoundIconIsNotAddedWhenTheSpecHasNone() {
        val after = assertNotNull(
            ManifestIconWriter.apply(manifest(" android:label=\"x\""), ManifestIconEdit("@mipmap/ic_launcher", null)),
        )
        assertTrue(!after.contains("roundIcon"), after)
    }

    @Test
    fun aSelfClosingApplicationTagStillTakesTheAttributes() {
        val text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="x"/>
            </manifest>
        """.trimIndent()
        val after = assertNotNull(ManifestIconWriter.apply(text, edit))
        assertTrue(after.contains("""android:icon="@mipmap/ic_launcher""""), after)
        assertTrue(after.trimEnd().endsWith("</manifest>"), after)
    }

    @Test
    fun singleQuotedValuesAreHandled() {
        val text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:icon='@mipmap/old'/>
            </manifest>
        """.trimIndent()
        val after = assertNotNull(ManifestIconWriter.apply(text, edit))
        assertTrue(after.contains("@mipmap/ic_launcher"), after)
        assertTrue(!after.contains("@mipmap/old"), after)
    }

    @Test
    fun aTagThatMerelyStartsTheSameWayIsNotMistakenForApplication() {
        val text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <applicationExtras android:icon="@mipmap/decoy"/>
                <application android:label="x"/>
            </manifest>
        """.trimIndent()
        val after = assertNotNull(ManifestIconWriter.apply(text, edit))
        assertTrue(after.contains("@mipmap/decoy"), "the lookalike element is untouched: $after")
        assertTrue(after.contains("""android:label="x""""), after)
        assertEquals(1, Regex("""android:icon="@mipmap/ic_launcher"""").findAll(after).count(), after)
    }

    @Test
    fun aGreaterThanInsideAnAttributeValueDoesNotEndTheTag() {
        val text = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="a > b" android:icon="@mipmap/old"/>
            </manifest>
        """.trimIndent()
        val after = assertNotNull(ManifestIconWriter.apply(text, edit))
        assertTrue(after.contains("@mipmap/ic_launcher"), after)
    }

    @Test
    fun aManifestWithNoApplicationElementIsReportedAsUnchangeable() {
        val text = """<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>"""
        assertNull(ManifestIconWriter.apply(text, edit))
    }

    @Test
    fun theCurrentIconCanBeReadBack() {
        assertEquals(
            "@mipmap/existing",
            ManifestIconWriter.currentIcon(manifest(" android:icon=\"@mipmap/existing\"")),
        )
        assertNull(ManifestIconWriter.currentIcon(manifest(" android:label=\"x\"")))
    }
}
