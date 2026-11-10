package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A user's release build with minify on, shrunk by the R8 INSIDE the IDE. The IDE ships R8 twice: a separate
 * dex asset its forked R8 runs from, and its own classes, which in-process shrinking uses. Only the second goes
 * through the IDE's own R8 pass (with R8's internals renamed), so the setting is forced to in-process here.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseBuildTest : E2eTest() {

    override fun seed() = DEFAULT_SEED + mapOf("settings.buildRuntime.r8Mode" to "inprocess")

    @Test
    fun minifiedReleaseBundleBuildsWithInProcessR8() {
        val name = uniqueName("Rel")
        createProject("Android App", name)

        // Minify has no switch in the UI: users set it in module.toml, which is what this does (with the app
        // stopped, so it reads the edit when the project opens again).
        val toml = "${projectDir(name)}/app/module.toml"
        val text = readDeviceFile(toml) // while the app runs: what it just wrote may not be visible yet
        shell("am force-stop $APP")
        val release = text.indexOf("\"release\"").takeIf { it >= 0 } ?: error("no release build type in $toml:\n$text")
        val flag = text.indexOf("minifyEnabled = false", release).takeIf { it >= 0 }
            ?: error("release has no minifyEnabled = false in $toml:\n$text")
        writeDeviceFile(toml, text.replaceRange(flag, flag + "minifyEnabled = false".length, "minifyEnabled = true"))
        restartAndOpen(name)

        run("bundleRelease (.aab) · app")
        awaitBuildSucceeded()
        // A shrunk build leaves R8's mapping behind; an unshrunk one doesn't.
        val mapping = shell("find ${projectDir(name)}/app/build -name mapping.txt")
        if ("mapping.txt" !in mapping) failWithScreen("the release build succeeded but R8 never ran (no mapping.txt)")
    }
}
