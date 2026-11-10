package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A plugin shipped as its own app (:samples:hello-plugin, bundled into this test APK) loads into the minified
 * IDE: its classes link against the IDE's dev.ide SPI, Compose and stdlib by name, which is exactly what a
 * missing keep rule breaks.
 */
@RunWith(AndroidJUnit4::class)
class PluginTest : E2eTest() {

    // Pre-consent so the plugin loads at startup without the review + restart round trip.
    override fun seed() = DEFAULT_SEED + mapOf("plugins.consented" to PLUGIN_ID)

    override fun beforeLaunch() {
        val staged = File(instrumentation.context.externalMediaDirs.first().apply { mkdirs() }, "hello-plugin.apk")
        instrumentation.context.assets.open("hello-plugin.apk").use { input -> staged.outputStream().use(input::copyTo) }
        shell("cp ${staged.absolutePath} /data/local/tmp/hello-plugin.apk")
        val result = shell("pm install -r -t /data/local/tmp/hello-plugin.apk")
        check("Success" in result) { "installing the sample plugin failed: $result" }
    }

    @After
    fun uninstallPlugin() {
        shell("pm uninstall $PLUGIN_PKG")
    }

    @Test
    fun samplePluginLoads() {
        openSettings()
        clickObject(scrollTo("Plugins"))
        click("Installed")
        await("Hello Plugin", 30_000)
        val status = awaitAny("On", "Didn't load", "Needs review", timeoutMs = 30_000)
        if (status != "On") failWithScreen("Hello Plugin status is \"$status\", expected On")
        if (findNow(Regex("^Did not load.*")) != null) failWithScreen("Hello Plugin did not load")
    }

    private companion object {
        const val PLUGIN_ID = "com.example.hello"
        const val PLUGIN_PKG = "com.example.hello"
    }
}
