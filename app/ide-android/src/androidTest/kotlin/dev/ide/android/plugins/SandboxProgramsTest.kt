package dev.ide.android.plugins

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A program a sandboxed plugin packages runs from the directory [DevApkPluginSource.unpackNativeLibs] gives it,
 * through the IDE's `libsandboxexec.so` and the system linker, with nothing installed. Run in the app's own
 * process (untrusted_app), where exec of a file in the app's storage is refused (reported: `error=13` from a C#
 * plugin's compiler in the sandbox).
 *
 * The "plugin" packages the device's `/system/bin/sh`, a real PIE program like the ones the NDK builds, and the
 * IDE's own aapt2, a static one.
 */
@RunWith(AndroidJUnit4::class)
class SandboxProgramsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pluginApk(): File {
        val abi = Build.SUPPORTED_ABIS.first()
        val apk = File(SandboxProcess.stateDir(context), "plugin-probe-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            fun add(name: String, from: File) {
                zip.putNextEntry(ZipEntry("lib/$abi/$name")); from.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            add("libprobe.so", File("/system/bin/sh"))
            add("libstatic.so", File(context.applicationInfo.nativeLibraryDir, "libaapt2.so"))
            zip.putNextEntry(ZipEntry("lib/$abi/libdata.so")); zip.write("not a program".toByteArray()); zip.closeEntry()
        }
        return apk
    }

    private fun run(vararg command: String): Pair<Int, String> {
        val p = ProcessBuilder(*command).redirectErrorStream(true).apply { environment()["PROBE_ENV"] = "kept" }.start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test
    fun aPackagedProgramRunsThroughTheSystemLinker() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val apk = pluginApk()
        try {
            val launcher = requireNotNull(DevApkPluginSource.programLauncher(context)) { "libsandboxexec.so is installed with the IDE" }
            val dir = requireNotNull(DevApkPluginSource.unpackNativeLibs(apk, launcher)).toFile()
            val probe = File(dir, "libprobe.so")
            assertTrue("the program's name starts the launcher", Files.isSymbolicLink(probe.toPath()))

            val (code, out) = run(probe.absolutePath, "-c", "echo SANDBOX_PROBE_OK \$PROBE_ENV \$0; exit 7", "arg")
            assertTrue(out, "SANDBOX_PROBE_OK kept arg" in out)
            assertEquals(out, 7, code)

            // A library stays a plain file for System.load.
            assertFalse(Files.isSymbolicLink(File(dir, "libdata.so").toPath()))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun aStaticProgramIsLeftAsItIsAndCannotRun() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val apk = pluginApk()
        try {
            val dir = requireNotNull(DevApkPluginSource.unpackNativeLibs(apk, DevApkPluginSource.programLauncher(context))).toFile()
            val static = File(dir, "libstatic.so")
            assertFalse(Files.isSymbolicLink(static.toPath()))
            try {
                run(static.absolutePath, "version")
                fail("Android 10+ should refuse to run a program from the app's own storage")
            } catch (e: IOException) {
                assertTrue(e.toString(), "error=13" in e.toString())
            }
        } finally {
            apk.delete()
        }
    }
}
