package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device Android toolchain end to end: aapt2, ecj/kotlinc (+ the Compose compiler plugin), D8, apksig,
 * the package installer hand-off, bundletool for .aab, and the Compose preview's interpreter + :preview process.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBuildTest : E2eTest() {

    @Test
    fun javaAndroidAppBuildsInstallsAndBundles() {
        val name = uniqueName("JApp")
        val pkg = createProject("Android App", name)
        run("Run Debug · app")
        installThroughSystemInstaller(pkg)
        // The IDE launches the app it just installed; come back to the IDE (it reopens the project).
        poll(60_000) { device.findObject(By.pkg(pkg)) }
        launchApp()
        Interruptions.dismissAll(this)
        await(name, PROJECT_TIMEOUT)

        run("bundleDebug (.aab) · app")
        awaitBuildSucceeded()
    }

    @Test
    fun composeAppPreviewsAndInstalls() {
        val name = uniqueName("CApp")
        val pkg = createProject("Jetpack Compose App", name)
        // The template resolves Compose from Maven before the editor can analyse or preview it.
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }
        openFile("MainActivity")

        click("Preview", BUILD_TIMEOUT)
        // The render reaches the pane as frames from the :preview process (a TextureView that accessibility doesn't
        // expose), so "rendered" is that process's own first-frame log line with no error, wait or failure on
        // screen. The pane's status text is reachable by walking the tree (screenText), not by selector lookups.
        // A brief "not interpretable" while the template's libraries finish indexing is tolerated; one that lasts
        // is the bug this guards (a lookup that missed before the libraries arrived stayed memoized, so the
        // preview said `Text` had 0 candidates for good).
        var notInterpretableSince = 0L
        val rendered = poll(BUILD_TIMEOUT, 1_000) {
            val screen = screenText()
            if ("Preview failed to render" in screen) failWithScreen("Compose preview failed to render")
            if ("Preview not interpretable" in screen) {
                val now = android.os.SystemClock.uptimeMillis()
                if (notInterpretableSince == 0L) notInterpretableSince = now
                if (now - notInterpretableSince > 60_000) {
                    // The problems chip handles taps without click semantics: tap the icon itself.
                    device.findObject(By.desc("Preview problems"))?.click()
                    Thread.sleep(2_000)
                    failWithScreen("Compose preview stayed not interpretable for a minute: ${screenText().substringAfter("Copy |", "").trim()}")
                }
                return@poll null
            }
            notInterpretableSince = 0L
            (previewFramesRendered() && "Preparing preview" !in screen).takeIf { it }
        }
        if (rendered == null) failWithScreen("Compose preview never rendered a frame")
        Thread.sleep(3_000)
        if ("Preview failed to render" in screenText()) failWithScreen("Compose preview failed to render")

        click("Code")
        run("Run Debug · app")
        installThroughSystemInstaller(pkg)
    }

    /** Whether the :preview process has drawn a frame since the test cleared logcat. */
    private fun previewFramesRendered(): Boolean = "first frame" in shell("logcat -d -s CAPreviewTiming:I")

    /** Waits for Android's install confirmation, accepts it, and checks the package really landed. */
    private fun installThroughSystemInstaller(pkg: String) {
        val installer = By.pkg(Pattern.compile(".*packageinstaller.*")).text(Pattern.compile("(?i)install"))
        // Either the build hands the APK to the installer, or it fails: then report why (awaitBuildSucceeded reads
        // the Problems, Log and Steps tabs) instead of waiting out the timeout for an installer that never comes.
        val install = poll(BUILD_TIMEOUT, 1_000) {
            device.findObject(installer) ?: run {
                if (findNow(Regex("^(Build failed|Failed)$")) != null) awaitBuildSucceeded()
                null
            }
        } ?: failWithScreen("the system installer never appeared")
        install.click()
        val installed = poll(120_000) { shell("pm list packages $pkg").takeIf { "package:$pkg" in it } }
        assertTrue("$pkg was not installed after the system installer accepted it", installed != null)
    }
}
