package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiJavaFile
import dev.ide.lang.java.index.JavaSourceIndexer
import dev.ide.psi.IntellijPsiHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Drives IntelliJ's **Java** PSI from many threads ON ART, settling what the desktop `ConcurrentParseSpikeTest`
 * cannot: does the Java PSI parse at all on ART (the `:lang-java` backend's premise), and does the shared env
 * hold up under many concurrent callers?
 *
 * This began as the "is **concurrent** `buildTree` ART-safe after a single-threaded warm-up?" experiment (the
 * hypothesis being that the ART concurrent-parse SIGSEGV was only first-touch lazy init). It passed here on
 * Android 8.0, and source indexing was migrated onto a shared-read-lock path on the strength of that. **The
 * field disproved it**: a 32-bit ART tombstone (Infinix X6823C, Android 12) caught a native SIGSEGV inside a
 * concurrent index `buildTree`. Concurrent tree building is forbidden again — [IntellijPsiHost.parseStructural]
 * serializes — so a clean pass here no longer licenses parallel `buildTree`; it only confirms Java PSI works on
 * ART and that concurrent callers funnel through the lock correctly. A pass on ONE device is not evidence that
 * a lock-free parse is safe on every ART build.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.JavaPsiConcurrentArtSpikeTest
 *     adb logcat -s JavaPsiConcurrentArtSpike
 */
@RunWith(AndroidJUnit4::class)
class JavaPsiConcurrentArtSpikeTest {

    @Test
    fun concurrentJavaParseIsSafeOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "java-psi-concurrent-art").apply { deleteRecursively(); mkdirs() }
        // IntelliJ-core reads its EP descriptors from a real FS path on ART; publish the extracted resource
        // home before the env stands up (same mechanism as XmlPsiArtSpikeTest / the Kotlin spike).
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        Log.i(TAG, "kotlinc.art.home = $home")

        // 1. Stand up the shared env and force ALL Java lazy init single-threaded (the warm-up hypothesis).
        IntellijPsiHost.warmUp()
        val warm = JavaSourceIndexer.parse("package w;\npublic class Warm { void m(){ int x = 1; } }")
        assertTrue(
            "single-threaded Java PSI parse must work on ART before testing concurrency; got ${warm.decls.map { it.name }}",
            warm.decls.any { it.name == "Warm" && it.kind == JavaSourceIndexer.DeclKind.CLASS },
        )
        Log.i(TAG, "single-threaded Java parse OK on ART (${warm.decls.size} decls)")

        // 2. Hammer structural parses from many threads (they serialize inside the host's parse lock).
        val n = 300
        val sources = (0 until n).map { i ->
            i to "package p$i;\npublic class C$i extends B$i { public int m$i(String a){ return $i; } int f$i; }"
        }
        val pool = Executors.newFixedThreadPool(6)
        val correct = ConcurrentHashMap<Int, Boolean>()
        val errors = CopyOnWriteArrayList<Throwable>()
        try {
            sources.map { (i, src) ->
                pool.submit {
                    try {
                        val d = IntellijPsiHost.parseStructural("C$i.java", JavaLanguage.INSTANCE, src) { psi ->
                            JavaSourceIndexer.declsOf(psi as PsiJavaFile)
                        }
                        correct[i] = d.decls.any { it.name == "C$i" } &&
                            d.decls.any { it.name == "m$i" } && d.decls.any { it.name == "f$i" }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(120, TimeUnit.SECONDS)
        }

        Log.i(TAG, "concurrent Java parse on ART: ${correct.size}/$n produced, ${errors.size} errors")
        if (errors.isNotEmpty()) Log.e(TAG, "first concurrent-parse error", errors.first())
        assertTrue("concurrent parse threw on ART: ${errors.take(3).map { it.toString() }}", errors.isEmpty())
        assertEquals("every source should have produced a result", n, correct.size)
        assertTrue("a concurrent parse produced wrong declarations on ART", correct.values.all { it })
    }

    /** Extract the kotlinc-resources.zip asset (the platform's non-class resources) into [home]. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively()
        home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) outFile.mkdirs()
                        else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    private companion object {
        const val TAG = "JavaPsiConcurrentArtSpike"
    }
}
