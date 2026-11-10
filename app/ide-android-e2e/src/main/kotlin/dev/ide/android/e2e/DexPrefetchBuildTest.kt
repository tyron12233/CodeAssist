package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The usual case for a new project: the user looks around before building. By then the IDE has downloaded the
 * project's prebuilt library dex (the prefetch armed when dependencies resolve), so the first build dexes and
 * downloads nothing for its libraries. Fresh app data, so nothing is in the caches from an earlier test.
 *
 * Runs against a staging repository passed as `-e dexPackUrl <base>`; skipped without one (see DexRepositoryBuildTest).
 */
@RunWith(AndroidJUnit4::class)
class DexPrefetchBuildTest : E2eTest() {
    private val packUrl: String? = InstrumentationRegistry.getArguments().getString("dexPackUrl")

    override val clearAppData = true
    override fun seed() = DEFAULT_SEED + (packUrl?.let { mapOf("settings.buildRuntime.dexPackUrl" to it) } ?: emptyMap())

    @Test
    fun aBuildAfterThePrefetchDownloadsNothing() {
        assumeTrue("needs -e dexPackUrl <staging pack base url>", packUrl != null)
        val name = uniqueName("Pre")
        createProject("Jetpack Compose App", name)
        val dir = projectDir(name)
        poll(PROJECT_TIMEOUT * 2, 2_000) {
            shell("cat $dir/.platform/libraries.json $dir/.platform/workspace.json").takeIf { "material3" in it }
        } ?: failWithScreen("Compose never resolved")
        // The prefetch reports when the libraries are in the shared cache.
        poll(BUILD_TIMEOUT, 2_000) {
            Regex("""dex prefetch: app — (\d+) library""").findAll(shell("logcat -d -s System.out:I"))
                .sumOf { it.groupValues[1].toInt() }.takeIf { it >= 60 }
        } ?: failWithScreen("the libraries were never prefetched")
        poll(PROJECT_TIMEOUT) { findNow("Resolving dependencies…").let { if (it == null) true else null } }

        shell("logcat -c")
        run("bundleDebug (.aab) · app")
        awaitBuildSucceeded()

        val log = shell("logcat -d -s System.out:I")
        // Only the build process's own fetch counts: the prefetch logged its lines from the UI process earlier.
        val buildPid = Regex("""\S+\s+\S+\s+(\d+)\s+\d+ I System.out: \[INFO] ide.mem: build '.*' start""").find(log)?.groupValues?.get(1)
        val fetched = log.lineSequence().filter { buildPid != null && Regex("""\s$buildPid\s""").containsMatchIn(it) }
            .mapNotNull { Regex("""dexrepo: dex repository: (\d+) of""").find(it)?.groupValues?.get(1)?.toInt() }.sum()
        android.util.Log.i("E2E", "after prefetch: build ${buildSeconds(log)}s, ${dexBuilderSeconds(log)}s dexBuilder, $fetched fetched during the build")
        org.junit.Assert.assertTrue("the build still downloaded $fetched libraries", fetched <= 2)
    }

    private fun at(log: String, pattern: String) = Regex("""(\d\d):(\d\d):(\d\d)\.(\d\d\d).*$pattern""").findAll(log).lastOrNull()
        ?.destructured?.let { (h, m, s, ms) -> ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong() }

    private fun buildSeconds(log: String): Long {
        val start = at(log, "ide.mem: build '.*' start") ?: return -1
        val end = at(log, "ide.mem: build '.*' peak") ?: return -1
        return (end - start) / 1000
    }

    private fun dexBuilderSeconds(log: String): Long {
        val start = at(log, "ide.mem: task :app:dexBuilderDebug") ?: return -1
        val end = at(log, "ide.mem: task :app:mergeExtDexDebug") ?: at(log, "ide.mem: task :app:mergeProjectDexDebug") ?: return -1
        return (end - start) / 1000
    }
}
