package dev.ide.android.support.resources

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidManifestTest {

    @Test
    fun parsesPackageSdkPermissionsAndComponents() {
        val dir = createTempDirectory("manifest")
        val file = dir.resolve("AndroidManifest.xml")
        Files.writeString(file, """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
              <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="34"/>
              <uses-permission android:name="android.permission.INTERNET"/>
              <application>
                <activity android:name=".MainActivity"/>
                <service android:name="com.example.app.SyncService"/>
              </application>
            </manifest>
        """.trimIndent())

        val info = AndroidManifestParser.parse(file)!!
        assertEquals("com.example.app", info.packageName)
        assertEquals(24, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertTrue("android.permission.INTERNET" in info.permissions)
        // relative `.MainActivity` resolves against the package; an already-qualified name is kept.
        assertTrue(ManifestComponent("activity", "com.example.app.MainActivity") in info.components)
        assertTrue(ManifestComponent("service", "com.example.app.SyncService") in info.components)
    }

    @Test
    fun returnsNullForMissingFile() {
        assertNull(AndroidManifestParser.parse(createTempDirectory("m").resolve("nope.xml")))
    }
}
