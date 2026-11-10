package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A new Compose app's first build takes its libraries pre-dexed, and the editor their index segments, from the
 * library repository instead of producing them on the phone (dexing alone was ~85 s of a ~95 s build on a 2 GB
 * device).
 *
 * Runs against a staging repository passed as `-e dexPackUrl <base>` (e.g. `python3 -m http.server` over a
 * repository `codeassist dexrepo` wrote, reached from the emulator as `http://10.0.2.2:<port>`); skipped without one,
 * so the suite never depends on what is currently published. Also proves the device finds libraries by the same
 * coordinates and content hashes the host producer wrote: a mismatch would show as 0 libraries arriving.
 */
@RunWith(AndroidJUnit4::class)
class DexRepositoryBuildTest : E2eTest() {
    private val packUrl: String? = InstrumentationRegistry.getArguments().getString("dexPackUrl")

    override fun seed() = DEFAULT_SEED + (packUrl?.let { mapOf("settings.buildRuntime.dexPackUrl" to it) } ?: emptyMap())

    @Test
    fun aNewComposeAppsLibrariesArrivePreDexed() {
        assumeTrue("needs -e dexPackUrl <staging pack base url>", packUrl != null)
        val name = uniqueName("Pack")
        createProject("Jetpack Compose App", name)
        val dir = projectDir(name)
        poll(PROJECT_TIMEOUT * 2, 2_000) {
            shell("cat $dir/.platform/libraries.json $dir/.platform/workspace.json").takeIf { "material3" in it }
        } ?: failWithScreen("Compose never resolved")
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }

        run("bundleDebug (.aab) · app")
        awaitBuildSucceeded()

        val log = shell("logcat -d -s System.out:I")
        val arrived = Regex("""dex repository: (\d+) of (\d+) library jar\(s\) arrived pre-dexed in (\d+)ms""").find(log)
            ?: failWithScreen("the build never consulted the dex repository")
        val (got, wanted, ms) = arrived.destructured
        android.util.Log.i("E2E", "dex repository: $got of $wanted in ${ms}ms; ${dexBuilderSeconds(log)}s dexBuilder")
        // Libraries the producer didn't see (the app-log bridge, anything version-skewed) still dex locally; the
        // template's own libraries must come from the repository.
        assertTrue("only $got of $wanted libraries came from the repository", got.toInt() >= wanted.toInt() - 3)

        // The editor's index passes (one per classpath change while the template resolves) take the libraries'
        // prebuilt segments from the same repository: the largest pass should have found them there.
        val indexed = Regex("""index repository: (\d+) of (\d+) library artifact\(s\) arrived pre-indexed in (\d+)ms""")
            .findAll(shell("logcat -d -s System.out:I")).map { it.destructured }.maxByOrNull { it.component2().toInt() }
            ?: failWithScreen("the editor's index never consulted the repository")
        val (iGot, iWanted, iMs) = indexed
        android.util.Log.i("E2E", "index repository: $iGot of $iWanted in ${iMs}ms")
        assertTrue("only $iGot of $iWanted libraries' indexes came from the repository", iGot.toInt() >= iWanted.toInt() - 3)
        // A Maven AAR's res/ folder is keyed by the AAR and published with it: none should be indexed here.
        val resBuilt = Regex("""index\.perf: built res: (\S+)""").findAll(shell("logcat -d -s System.out:I")).map { it.groupValues[1] }.toList()
        android.util.Log.i("E2E", "res folders indexed on the phone: ${resBuilt.size} ${resBuilt.take(5)}")
        assertTrue("library res folders were indexed on the phone: $resBuilt", resBuilt.size <= 2)
    }

    /** dexBuilder's wall time from the build process's task-start lines, for the report. */
    private fun dexBuilderSeconds(log: String): Long {
        fun at(task: String) = Regex("""(\d\d):(\d\d):(\d\d)\.(\d\d\d).*ide\.mem: task :app:$task""").find(log)
            ?.destructured?.let { (h, m, s, ms) -> ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong() }
        val start = at("dexBuilderDebug") ?: return -1
        val end = at("mergeProjectDexDebug") ?: at("mergeExtDexDebug") ?: return -1
        return (end - start) / 1000
    }

    private fun assertTrue(message: String, ok: Boolean) = org.junit.Assert.assertTrue(message, ok)
}
