package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app creates its external files directory itself, before asking the platform for it. The platform's
 * `getExternalFilesDir` creates a missing directory while holding the lock every directory getter of the
 * context shares, which on slow storage froze the main thread at first launch (an ANR seen in the field).
 * That only works if the path the app works out is the platform's, and if the app is allowed to create it.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.ExternalHomeArtSpike
 */
@RunWith(AndroidJUnit4::class)
class ExternalHomeArtSpike {

    @Test
    fun theAppCanCreateItsExternalFilesDirWhereThePlatformPutsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val platform = context.getExternalFilesDir(null) ?: error("no external storage on this device")
        val expected = AndroidIde.expectedExternalFilesDir(context) ?: error("the primary volume reads as not mounted")
        assertEquals(platform.canonicalPath, expected.canonicalPath)

        // As on a first launch: the directory is not there yet, and the app makes it without the platform.
        assertTrue("could not remove $platform", platform.deleteRecursively() && !platform.exists())
        val t0 = System.nanoTime()
        val home = AndroidIde.externalHome(context)
        Log.i("ExternalHomeArt", "externalHome with the directory missing: ${(System.nanoTime() - t0) / 1_000_000}ms -> $home")
        assertEquals(platform.canonicalPath, home.canonicalPath)
        assertTrue(home.isDirectory && home.canWrite())

        assertTrue("the app's own mkdirs must work on a missing directory", platform.deleteRecursively())
        assertTrue(expected.mkdirs() && expected.isDirectory)
    }
}
