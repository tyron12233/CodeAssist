package dev.ide.android.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.core.SwingApiStubs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.jar.JarFile

/**
 * The compile-time `java.awt`/`javax.swing` API has to reach the device, or a Swing project cannot be built
 * there: `android.jar` carries no Swing and, unlike the desktop, there is no JDK behind it to fall back on.
 *
 * The jar rides in the APK as an ordinary java resource of `:ide-core`, which is the part worth proving on a
 * real device rather than assuming: resource packaging is exactly where an artifact silently goes missing.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.SwingApiStubsArtSpike
 */
@RunWith(AndroidJUnit4::class)
class SwingApiStubsArtSpike {

    @Test
    fun theSwingApiJarIsBundledAndExtractableOnDevice() {
        val jar = SwingApiStubs.bundled()
        assertNotNull("the API jar did not survive into the APK's resources", jar)
        Log.i("SwingApiStubsArt", "extracted to $jar")

        val entries = JarFile(jar!!.toFile()).use { file -> file.entries().toList().map { it.name } }
        Log.i("SwingApiStubsArt", "${entries.size} API classes on device")

        // The types the templates' first few lines name; if these resolve, a Swing project compiles here.
        for (expected in listOf(
            "java/awt/Color.class", "java/awt/Graphics2D.class", "java/awt/BorderLayout.class",
            "javax/swing/JFrame.class", "javax/swing/JButton.class", "javax/swing/WindowConstants.class",
        )) {
            assertTrue("$expected missing from the on-device API jar", expected in entries)
        }
    }
}
