package dev.ide.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestPermissionEditsTest {

    private fun add(text: String, vararg permissions: String): String? =
        ManifestPermissionEdits.insertion(text, permissions.toList())?.let { e ->
            text.substring(0, e.offset) + e.newText + text.substring(e.offset + e.oldLength)
        }

    @Test
    fun goesAfterTheLastDeclaredPermission() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application android:label="x" />
            </manifest>
        """.trimIndent()
        assertEquals("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <uses-permission android:name="android.permission.CAMERA" />
                <application android:label="x" />
            </manifest>
        """.trimIndent(), add(manifest, "android.permission.CAMERA"))
    }

    @Test
    fun goesBeforeTheApplicationWhenThereIsNone() {
        val manifest = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n    <application>\n    </application>\n</manifest>"
        assertEquals(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n" +
                "    <uses-permission android:name=\"android.permission.CAMERA\" />\n" +
                "    <uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n\n" +
                "    <application>\n    </application>\n</manifest>",
            add(manifest, "android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
        )
    }

    @Test
    fun anAlreadyDeclaredPermissionIsNotAddedAgain() {
        val manifest = "<manifest>\n    <uses-permission android:name=\"android.permission.CAMERA\"/>\n</manifest>"
        assertNull(add(manifest, "android.permission.CAMERA"))
        assertEquals(
            "<manifest>\n    <uses-permission android:name=\"android.permission.CAMERA\"/>\n" +
                "    <uses-permission android:name=\"android.permission.VIBRATE\" />\n</manifest>",
            add(manifest, "android.permission.CAMERA", "android.permission.VIBRATE"),
        )
    }
}
