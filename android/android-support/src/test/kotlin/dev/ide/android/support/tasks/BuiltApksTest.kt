package dev.ide.android.support.tasks

import dev.ide.android.support.BuiltApks
import dev.ide.android.support.tools.SigningConfig
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.engine.BuildCache
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.build.engine.TaskExecutorImpl
import dev.ide.build.engine.TaskGraphImpl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltApksTest {
    private val dir: Path = Files.createTempDirectory("built-apks")
    private val ledger = dir.resolve("ledger")

    @AfterTest
    fun tearDown() = BuiltApks.init(null)

    @Test
    fun onlyTheExactFileTheBuildRecordedIsAccepted() {
        BuiltApks.init(ledger)
        val apk = write("app/build/outputs/apk/debug/app-debug.apk", "built")
        BuiltApks.record(apk)
        assertTrue(BuiltApks.isRecorded(apk))
        assertTrue(BuiltApks.looksRecorded(apk))

        // Same bytes at another path were never recorded there.
        val copy = write("other/build/outputs/apk/debug/app-debug.apk", "built")
        assertFalse(BuiltApks.isRecorded(copy), "a copy at another path")

        // A clone or import replacing the file at the recorded path.
        write("app/build/outputs/apk/debug/app-debug.apk", "downloaded")
        assertFalse(BuiltApks.isRecorded(apk), "different bytes at the recorded path")
    }

    @Test
    fun nothingIsAcceptedWithoutALedger() {
        BuiltApks.init(null)
        val apk = write("app/build/outputs/apk/debug/app-debug.apk", "built")
        BuiltApks.record(apk)
        assertFalse(BuiltApks.isRecorded(apk))
    }

    @Test
    fun theSignStepReSignsOverAnApkItDidNotRecord() {
        BuiltApks.init(ledger)
        val unsigned = write("app/build/intermediates/app-unsigned.apk", "unsigned")
        val signed = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
        var signs = 0
        val task = { SignApkTask(TaskName(":app:sign"), unsigned, signed, config, { u, s, _ ->
            signs++
            Files.createDirectories(s.parent)
            Files.copy(u, s, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            ToolResult.ok()
        }) }
        val cache = BuildCache(dir.resolve("cache"))
        fun build() = runBlocking { TaskExecutorImpl(cache).execute(TaskGraphImpl(listOf(task())), SimpleTaskContext(), 1) }

        assertTrue(build().succeeded)
        assertTrue(BuiltApks.isRecorded(signed), "the build records what it signs")
        // Settle: the first build's inputs predate its own record, so one more run may re-sign.
        build()
        val settled = signs
        build()
        assertEquals(settled, signs, "a recorded, unchanged APK stays up to date")

        // A cloned file lands at the output path: the next build signs again and records the new one.
        Files.write(signed, "from a clone".toByteArray())
        Files.setLastModifiedTime(signed, FileTime.fromMillis(1_000))
        assertTrue(build().succeeded)
        assertEquals(settled + 1, signs, "re-signed over the unrecorded APK")
        assertTrue(BuiltApks.isRecorded(signed))
    }

    @Test
    fun anApkFromBeforeTheRecordIsSignedAgainOnce() {
        val unsigned = write("app/build/intermediates/app-unsigned.apk", "unsigned")
        val signed = dir.resolve("app/build/outputs/apk/debug/app-debug.apk")
        var signs = 0
        val cache = BuildCache(dir.resolve("cache"))
        fun build() = runBlocking {
            val task = SignApkTask(TaskName(":app:sign"), unsigned, signed, config, { u, s, _ ->
                signs++
                Files.createDirectories(s.parent)
                Files.copy(u, s, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                ToolResult.ok()
            })
            TaskExecutorImpl(cache).execute(TaskGraphImpl(listOf(task)), SimpleTaskContext(), 1)
        }

        // Built by a version with no record: the output is there and unchanged, but nothing vouches for it.
        BuiltApks.init(null)
        build()
        assertEquals(1, signs)

        BuiltApks.init(ledger)
        assertFalse(BuiltApks.isRecorded(signed))
        build()
        assertEquals(2, signs, "the first build with a record signs again")
        assertTrue(BuiltApks.isRecorded(signed), "and the phone can install the result")
    }

    private val config = SigningConfig(dir.resolve("debug.ks"), "android", "androiddebugkey", "android")

    private fun write(rel: String, text: String): Path =
        dir.resolve(rel).also { Files.createDirectories(it.parent); Files.write(it, text.toByteArray()) }
}
