package dev.ide.android.fork

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.AndroidIde
import dev.ide.android.R8ForkSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage of the WIRING that puts [ForkedKotlinCompiler] behind a real build.
 *
 * [ForkedKotlinCompilerTest] drives the compiler object directly, which proves the forked VM works but not
 * that the app uses it. The chain in between is entirely untested by that: `AndroidIde.createProjectManager`
 * builds the compiler, `ProjectManager` registers it as the `platform.kotlinCompilerBackend` port,
 * `IdeServices` resolves that port in preference to the in-process K2 compiler, and `BuildService`'s
 * `IncrementalKotlinCompiler` compiles through it. A break anywhere along it is invisible: the build still
 * succeeds, just in-process, silently giving up the bigger heap.
 *
 * So this compiles Kotlin through the REAL manager and then looks for the worker VM in the process table. A
 * `kotlinc-console` scratch project is the vehicle because it is a bundled template with only the bundled
 * stdlib on its classpath: it needs no network and no seeded project, so the test is self-contained.
 *
 * Run on a connected device:
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.fork.ForkedKotlinCompilerWiringTest
 *     adb logcat -s ForkedKotlincWiring
 */
@RunWith(AndroidJUnit4::class)
class ForkedKotlinCompilerWiringTest {

    /**
     * Compiling through the real [AndroidIde] manager must run kotlinc in a forked worker VM, not in-process.
     * Proven from outside the abstraction: no worker exists before the build, and one is serving compiles
     * after it.
     */
    @Test
    fun aRealBuildCompilesInTheWorkerVm() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no command-line VM launcher on this device", R8ForkSupport.launcher() != null)
        killWorkers()   // a worker left over from another test would make this vacuous
        assertEquals("a worker existed before the build", 0, workerPids().size)

        val manager = AndroidIde.createProjectManager(ctx)
        val services = manager.scratch(SCRATCH_KEY, templateId = SCRATCH_KEY)
        val module = requireNotNull(services.modules().firstOrNull()) { "scratch project has no module" }
        Log.i(TAG, "scratch project module = ${module.name}")

        val capture = runBlocking { services.runAndCapture(module.name, timeoutMs = COMPILE_TIMEOUT_MS) }
        Log.i(TAG, "runAndCapture: compiled=${capture.compiled} ran=${capture.ran} exit=${capture.exitCode} stdout=${capture.stdout.trim()}")
        capture.diagnostics.take(10).forEach { Log.i(TAG, "  | $it") }

        val workers = workerPids()
        Log.i(TAG, "WIRING RESULT: worker VMs after the build = ${workers.size} $workers")
        assertTrue("the scratch project did not compile: ${capture.diagnostics}", capture.compiled)
        assertTrue(
            "no forked Kotlin compiler VM ran: the build compiled in-process, so the " +
                "platform.kotlinCompilerBackend port is not reaching IncrementalKotlinCompiler",
            workers.isNotEmpty(),
        )
    }

    /**
     * [ProcessIdentity] decides which process may hold a worker. If it cannot read the process name it would
     * report "not the build process" everywhere, and the IDE process would never fork even with
     * separate-process builds turned off. Instrumentation runs against the app's main process, so the name
     * must resolve and must not look like the `:build` daemon.
     */
    @Test
    fun processIdentityResolvesInTheAppProcess() {
        val name = ProcessIdentity.processName
        Log.i(TAG, "process name = $name  isBuildProcess=${ProcessIdentity.isBuildProcess()}")
        assertNotNull("could not read the process name from /proc/self/cmdline", name)
        assertTrue("process name looks wrong: '$name'", name!!.startsWith(APP_ID))
        assertTrue("the instrumentation process must not report itself as :build", !ProcessIdentity.isBuildProcess())
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /** PIDs of every forked Kotlin compiler VM this app owns, found by their main class on the command line. */
    private fun workerPids(): List<Int> = runCatching {
        val marker = KotlincWorkerMain::class.java.name
        val proc = ProcessBuilder("sh", "-c", "ps -A -o PID,ARGS 2>/dev/null || ps -A")
            .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().readLines()
            .filter { marker in it }
            .mapNotNull { it.trim().substringBefore(' ').toIntOrNull() }
    }.getOrDefault(emptyList())

    private fun killWorkers() {
        workerPids().forEach { runCatching { android.os.Process.killProcess(it) } }
    }

    private companion object {
        const val TAG = "ForkedKotlincWiring"
        const val APP_ID = "com.tyron.code"
        const val SCRATCH_KEY = "kotlin-console"

        /** A cold worker plus a first compile; generous so a slow device does not fail on the clock. */
        const val COMPILE_TIMEOUT_MS = 180_000L
    }
}
