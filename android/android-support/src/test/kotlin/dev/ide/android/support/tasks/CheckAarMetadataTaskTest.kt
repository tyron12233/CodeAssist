package dev.ide.android.support.tasks

import dev.ide.build.BuildLogEntry
import dev.ide.build.BuildLogLevel
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `checkAarMetadata` also reports when the build is compiling against an OLDER platform than the module asked
 * for. `AndroidSdk.detect` falls back to the highest installed platform when the requested one is absent, so
 * a module set to compileSdk 36 on a machine that only has `android-34` builds against 34 and then fails on a
 * symbol that does not exist there, with nothing pointing at the cause. It stays a warning: that build is the
 * best this machine can do, and the IDE cannot install a platform for the user.
 */
class CheckAarMetadataTaskTest {

    @Test fun warnsWhenTheResolvedPlatformIsOlderThanCompileSdk() {
        val log = runTask(platformDir = "android-34", compileSdk = 36)

        // The severity rides on the entry's level, not on a "WARNING:" prefix glued to the text — the console
        // colours and filters by level, so a warning that only says it is one in prose is invisible to both.
        val warnings = log.filter { it.level == BuildLogLevel.WARN }
        assertTrue(warnings.isNotEmpty(), "an older platform must be reported as a warning: $log")
        assertTrue(warnings.any { "compileSdk 36 is not installed" in it.message }, "$log")
        assertTrue(
            warnings.any { "API 34" in it.message },
            "the level actually used belongs in the message: $log",
        )
    }

    @Test fun silentWhenThePlatformMatchesOrExceedsCompileSdk() {
        assertTrue(warningsOf(runTask(platformDir = "android-36", compileSdk = 36)).isEmpty())
        // A minor revision (`android-36.1`) is the same major level, and a newer platform is no problem either.
        assertTrue(warningsOf(runTask(platformDir = "android-36.1", compileSdk = 36)).isEmpty())
        assertTrue(warningsOf(runTask(platformDir = "android-37.0", compileSdk = 36)).isEmpty())
    }

    @Test fun theOnDeviceBundledJarHasNoPlatformDirAndIsNotReported() {
        // On device `android.jar` is a bundled asset sitting on its own, so there is no level to compare.
        assertTrue(warningsOf(runTask(platformDir = "codeassist", compileSdk = 36)).isEmpty())
    }

    private fun warningsOf(log: List<BuildLogEntry>) = log.filter { it.level == BuildLogLevel.WARN }

    /** Run the task against a platform jar under `platforms/<platformDir>/`, returning everything it logged. */
    private fun runTask(platformDir: String, compileSdk: Int): List<BuildLogEntry> =
        withTempDir("check-aar-meta") { tmp ->
            val jar = tmp.resolve("platforms").resolve(platformDir).resolve("android.jar")
            Files.createDirectories(jar.parent)
            val log = ArrayList<BuildLogEntry>()
            val result = runBlocking {
                CheckAarMetadataTask(
                    TaskName(":app:checkAarMetadata"), emptyList(), compileSdk, tmp.resolve("stamp.txt"), jar,
                ).execute(SimpleTaskContext(onLog = { log.add(it) }))
            }
            assertEquals(TaskResult.Success, result, "the platform check never fails the build")
            log
        }
}
