package dev.ide.android.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The edit-and-rebuild loop on a Compose app: a first build, a rebuild after changing one string in the activity,
 * and a rebuild with nothing changed. Reports each build's task timeline (from the build process's own log) as
 * `E2E` lines; fails only if a build fails. A measurement, not a threshold: device speed varies too much.
 */
@RunWith(AndroidJUnit4::class)
class RebuildTimingTest : E2eTest() {
    override val clearAppData = true

    @Test
    fun editAndRebuild() {
        val name = uniqueName("Loop")
        val pkg = createProject("Jetpack Compose App", name)
        val dir = projectDir(name)
        poll(PROJECT_TIMEOUT * 2, 2_000) {
            shell("cat $dir/.platform/libraries.json $dir/.platform/workspace.json").takeIf { "material3" in it }
        } ?: failWithScreen("Compose never resolved")
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }

        build("first")
        val main = "$dir/app/src/main/kotlin/${pkg.replace('.', '/')}/MainActivity.kt"
        val source = readDeviceFile(main)
        val edited = Regex("\"Hello[^\"]*\"").find(source)?.let { source.replaceFirst(it.value, "\"Hello, rebuild\"") }
            ?: source.replaceFirst("setContent", "/* edited */ setContent")
        writeDeviceFile(main, edited)
        Thread.sleep(3_000)   // the IDE notices the file change
        build("one-string edit")
        build("no change")
    }

    /** The Run path: an APK, handed to the system installer (dismissed here; only the build is timed). */
    @Test
    fun editAndRunApk() {
        val name = uniqueName("Apk")
        val pkg = createProject("Jetpack Compose App", name)
        val dir = projectDir(name)
        poll(PROJECT_TIMEOUT * 2, 2_000) {
            shell("cat $dir/.platform/libraries.json $dir/.platform/workspace.json").takeIf { "material3" in it }
        } ?: failWithScreen("Compose never resolved")
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }

        build("run, first", task = "Run Debug · app", installer = true)
        val main = "$dir/app/src/main/kotlin/${pkg.replace('.', '/')}/MainActivity.kt"
        val source = readDeviceFile(main)
        val edited = Regex("\"Hello[^\"]*\"").find(source)?.let { source.replaceFirst(it.value, "\"Hello, run\"") }
            ?: source.replaceFirst("setContent", "/* edited */ setContent")
        writeDeviceFile(main, edited)
        Thread.sleep(3_000)
        build("run, one-string edit", task = "Run Debug · app", installer = true)
    }

    private fun build(label: String, task: String = "bundleDebug (.aab) · app", installer: Boolean = false) {
        shell("logcat -c")
        run(task)
        if (installer) {
            // The build ends where the system installer takes over; time the build, then dismiss the installer.
            poll(BUILD_TIMEOUT, 1_000) { shell("logcat -d -s System.out:I").takeIf { "' peak:" in it } }
                ?: failWithScreen("$label: the build never finished")
            Thread.sleep(2_000)
            device.pressBack(); Thread.sleep(500); device.pressBack()
            launchApp(); Interruptions.dismissAll(this)
        } else {
            awaitBuildSucceeded()
        }
        Thread.sleep(1_000)
        val log = shell("logcat -d -s System.out:I")
        val tasks = Regex("""(\d\d):(\d\d):(\d\d)\.(\d\d\d) +\d+ +\d+ I System.out: \[INFO] ide.mem: (?:task :app:(\w+)|build '.*' (start|peak))""")
            .findAll(log).map { m ->
                val (h, mi, s, ms) = m.destructured
                (((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()) to (m.groupValues[5].ifEmpty { m.groupValues[6] })
            }.toList()
        if (tasks.isEmpty()) { Log.i("E2E", "$label: no timeline"); return }
        val t0 = tasks.first().first
        val total = (tasks.last().first - t0) / 1000.0
        // Each task's start offset; a task's duration is roughly the gap to the next start on the critical path.
        Log.i("E2E", "$label: ${"%.1f".format(total)}s — " + tasks.joinToString(" ") { (t, n) -> "$n@${"%.1f".format((t - t0) / 1000.0)}" })
    }
}
