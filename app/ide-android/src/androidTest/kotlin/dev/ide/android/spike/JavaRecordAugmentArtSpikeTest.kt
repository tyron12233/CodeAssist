package dev.ide.android.spike

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.psi.IntellijPsiHost
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Settles whether IntelliJ's record augmentation stands up ON ART — the open question left by `JavaRecordSupport`
 * (its capability gate can only be verified on a real device from here). Creating a [JavaEnvironment] runs
 * `JavaRecordSupport.ensureFor`, which registers `com.intellij.psi.impl.RecordAugmentProvider` on the app-level
 * augment EP plus a minimal `PomModel`/`TreeAspect` on the project, then probes a throwaway record. This test
 * then parses a real SOURCE record and asserts its SYNTHESIZED members materialized on-device: the component
 * accessors `x()`/`y()`, the 2-arg canonical constructor, and the backing fields.
 *
 * If they materialize, records get FULL support (resolution/go-to/hover/diagnostics) on Android, not just the
 * hand-rolled fallback. If they don't, `JavaRecordSupport` has disabled augmentation and the fallback carries
 * records — this test tells us which, on this ART build. android.jar is the platform (jdkHome=null → NO_JDK),
 * exactly like a real Android module.
 *
 *     ./gradlew :ide-android:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=dev.ide.android.spike.JavaRecordAugmentArtSpikeTest
 *     adb logcat -s JavaRecordAugmentArtSpike
 */
@RunWith(AndroidJUnit4::class)
class JavaRecordAugmentArtSpikeTest {

    @Test
    fun recordAugmentationStandsUpOnArt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(ctx.filesDir, "java-record-augment-art").apply { deleteRecursively(); mkdirs() }
        val home = provisionKotlincHome(ctx, File(work, "kotlinc-home"))
        System.setProperty("kotlinc.art.home", home.absolutePath)
        Log.i(TAG, "kotlinc.art.home=$home")

        IntellijPsiHost.warmUp()
        // Creating the env runs JavaRecordSupport.ensureFor (register PomModel + app-wide RecordAugmentProvider,
        // verify-once). No classpath: a record with `int` components is augmented purely structurally — the
        // accessors/ctor/fields are all `int`, so nothing needs `java.lang` resolution (NO_JDK, empty boot cp).
        val env = JavaEnvironment.create(emptyList(), emptyList(), jdkHome = null)

        val report = ArrayList<String>()
        try {
            val psi = env.parse("Point.java", "record Point(int x, int y) { public int sum() { return x + y; } }")
            val cls = psi.classes.firstOrNull()
            report += "record parsed=${cls != null} isRecord=${cls?.isRecord}"
            assertTrue("record class must parse on ART", cls != null && cls.isRecord)
            cls!!

            val methodNames = cls.methods.map { it.name }
            val ctorArity = cls.constructors.map { it.parameterList.parametersCount }
            val fieldNames = cls.fields.map { it.name }
            report += "methods=$methodNames"
            report += "constructors(arity)=$ctorArity"
            report += "fields=$fieldNames"
            Log.i(TAG, "record methods on ART       = $methodNames")
            Log.i(TAG, "record constructors (arity) = $ctorArity")
            Log.i(TAG, "record fields on ART        = $fieldNames")

            val hasAccessors = "x" in methodNames && "y" in methodNames
            val hasCanonicalCtor = ctorArity.contains(2)
            val hasBackingFields = "x" in fieldNames && "y" in fieldNames
            val live = hasAccessors && hasCanonicalCtor && hasBackingFields
            report += if (live) "VERDICT: RECORD AUGMENTATION IS LIVE ON ART"
                      else "VERDICT: augmentation NOT live on ART -> hand-rolled fallback in use"
            Log.i(TAG, report.last())

            assertTrue("component accessors x()/y() must materialize on ART; got methods=$methodNames", hasAccessors)
            assertTrue("2-arg canonical constructor must be synthesized on ART; got arity=$ctorArity", hasCanonicalCtor)
            assertTrue("backing fields x/y must be synthesized on ART; got fields=$fieldNames", hasBackingFields)
        } catch (t: Throwable) {
            report += "EXCEPTION: ${t.javaClass.simpleName}: ${t.message}"
            throw t
        } finally {
            env.close()
            // Deterministic pull point (logcat can rotate): <ext-files>/record-augment-report.txt.
            val out = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "record-augment-report.txt")
            runCatching { out.writeText(report.joinToString("\n") + "\n") }
            Log.i(TAG, "report written to $out")
        }
    }

    /** Extract the kotlinc-resources.zip asset into [home]. Tolerant: replaces a file that collides with a
     *  needed directory prefix, and never aborts extraction over a single unwritable entry. */
    private fun provisionKotlincHome(ctx: Context, home: File): File {
        home.deleteRecursively(); home.mkdirs()
        val canonicalHome = home.canonicalPath + File.separator
        ctx.assets.open("kotlinc-resources.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(home, entry.name)
                    if (outFile.canonicalPath.startsWith(canonicalHome)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            var p = outFile.parentFile
                            while (p != null && p.canonicalPath.startsWith(canonicalHome.trimEnd(File.separatorChar))) {
                                if (p.isFile) p.delete()
                                p = p.parentFile
                            }
                            outFile.parentFile?.mkdirs()
                            runCatching { outFile.outputStream().use { zis.copyTo(it) } }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return home
    }

    private companion object {
        const val TAG = "JavaRecordAugmentArtSpike"
    }
}
