package dev.ide.android.spike

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.CoreJavaApiAdditions
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.Vm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Java 17 to 21 programs in the bytecode VM, on ART: records, sealed types and pattern matching, the library
 * calls an older release lacks, virtual threads, `ScopedValue` and `StructuredTaskScope`. On a desktop JVM the
 * host has all of it, and :jvm-interp's tests only simulate a runtime that does not; here the runtime really
 * does not, so the stand-ins, the backports and the emulated threads are what answer.
 *
 * The fixtures and the answers they give on a real JVM are :jvm-interp's, staged by `bundleJava21SpikeAsset`.
 *
 *     JAVA_HOME=<Android Studio JBR> ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.Java21ArtSpike
 */
@RunWith(AndroidJUnit4::class)
class Java21ArtSpike {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun stage(): Path {
        val root = instrumentation.targetContext.cacheDir.toPath().resolve("java21-spike")
        root.toFile().deleteRecursively()
        copyAsset("java21", root)
        return root
    }

    private fun copyAsset(path: String, to: Path) {
        val assets = instrumentation.context.assets
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            Files.createDirectories(to.parent)
            assets.open(path).use { Files.copy(it, to) }
        } else {
            children.forEach { copyAsset("$path/$it", to.resolve(it)) }
        }
    }

    /** A VM over the staged fixtures. `Structured` is given the platform names of `ScopedValue` and
     *  `StructuredTaskScope`, as a program compiled on the device has them. */
    private fun vm(classes: Path) = Vm(
        source = ClassBytesSource { name ->
            classes.resolve("$name.class").takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
                ?.let { if (name.startsWith("$FIXTURES/Structured")) CoreJavaApiAdditions.toPlatformNames(it) else it }
        },
        policy = InterpretPolicy { it.startsWith("$FIXTURES/") },
        peerFactory = DexPeerFactory(),
    )

    @Test
    fun everyFeatureMatchesTheRealJvm() {
        val root = stage()
        val classes = root.resolve("classes")
        val expected = Properties().apply { Files.newInputStream(root.resolve("expected.properties")).use { load(it) } }
        // A runtime below Android 14 has no RecordComponent for getRecordComponents to return.
        val skipped = if (Build.VERSION.SDK_INT < 34) setOf("Java21#recordReflection", "Java21#recordAccessorReflection") else emptySet()
        val failures = ArrayList<String>()
        var ran = 0
        for (key in expected.stringPropertyNames().sorted()) {
            val probe = key.substringBefore('(')
            if (probe in skipped) continue
            val owner = "$FIXTURES/${probe.substringBefore('#')}"
            val method = probe.substringAfter('#')
            val argument = key.substringAfter('(', "").removeSuffix(")").toIntOrNull()
            val got = runCatching {
                // A fresh VM each time: a failure in one feature must not leave state behind for the next.
                if (argument == null) vm(classes).invokeStatic(owner, method, "()Ljava/lang/String;", emptyList())
                else vm(classes).invokeStatic(owner, method, "(I)Ljava/lang/String;", listOf(argument))
            }.getOrElse { "THROWS ${it::class.java.simpleName}: ${it.message?.take(300)}" }
            ran++
            val want = expected.getProperty(key)
            if (got != want) failures += "$key\n  want $want\n  got  $got"
        }
        Log.i(TAG, "API ${Build.VERSION.SDK_INT}: $ran features, ${failures.size} differ")
        failures.forEach { Log.e(TAG, it) }
        assertTrue("ran only $ran features", ran > 60)
        assertTrue("API ${Build.VERSION.SDK_INT}:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * How many emulated virtual threads this device carries at once. The cap is 2,000: that many must start, all
     * be alive together and finish, and one more than the cap must be refused with the message, not by the
     * process running out of memory. Logs the time and the memory the full load took.
     */
    @Test
    fun aFullLoadOfVirtualThreadsStartsAndTheNextIsRefused() {
        val classes = stage().resolve("classes")
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val t0 = System.nanoTime()
        val full = vm(classes).invokeStatic("$FIXTURES/ThreadLoad", "hold", "(I)Ljava/lang/String;", listOf(2_000))
        val ms = (System.nanoTime() - t0) / 1_000_000
        val after = runtime.totalMemory() - runtime.freeMemory()
        Log.i(TAG, "2000 virtual threads: '$full' in ${ms}ms, heap +${(after - before) / 1024}KB, max heap ${runtime.maxMemory() / (1024 * 1024)}MB")
        assertEquals("2000 none", full)

        val over = vm(classes).invokeStatic("$FIXTURES/ThreadLoad", "hold", "(I)Ljava/lang/String;", listOf(2_050))
        Log.i(TAG, "2050 virtual threads: '$over'")
        assertEquals("2000 UnsupportedOperationException", over)
    }

    private companion object {
        const val TAG = "Java21Art"
        const val FIXTURES = "dev/ide/jvm/fixtures"
    }
}
