package dev.ide.android.support.tasks

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

        assertTrue("WARNING" in log, "an older platform must be reported: $log")
        assertTrue("compileSdk 36 is not installed" in log, log)
        assertTrue("API 34" in log, "the level actually used belongs in the message: $log")
    }

    @Test fun silentWhenThePlatformMatchesOrExceedsCompileSdk() {
        assertTrue("WARNING" !in runTask(platformDir = "android-36", compileSdk = 36))
        // A minor revision (`android-36.1`) is the same major level, and a newer platform is no problem either.
        assertTrue("WARNING" !in runTask(platformDir = "android-36.1", compileSdk = 36))
        assertTrue("WARNING" !in runTask(platformDir = "android-37.0", compileSdk = 36))
    }

    @Test fun theOnDeviceBundledJarHasNoPlatformDirAndIsNotReported() {
        // On device `android.jar` is a bundled asset sitting on its own, so there is no level to compare.
        assertTrue("WARNING" !in runTask(platformDir = "codeassist", compileSdk = 36))
    }

    /** Run the task against a platform jar under `platforms/<platformDir>/`, returning everything it logged. */
    private fun runTask(platformDir: String, compileSdk: Int): String = withTempDir("check-aar-meta") { tmp ->
        val jar = tmp.resolve("platforms").resolve(platformDir).resolve("android.jar")
        Files.createDirectories(jar.parent)
        val log = StringBuilder()
        val result = runBlocking {
            CheckAarMetadataTask(
                TaskName(":app:checkAarMetadata"), emptyList(), compileSdk, tmp.resolve("stamp.txt"), jar,
            ).execute(SimpleTaskContext(log = { log.appendLine(it) }))
        }
        assertEquals(TaskResult.Success, result, "the platform check never fails the build")
        log.toString()
    }
}
