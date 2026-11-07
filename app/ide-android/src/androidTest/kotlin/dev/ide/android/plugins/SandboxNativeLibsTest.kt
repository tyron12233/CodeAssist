package dev.ide.android.plugins

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A program a sandboxed plugin packages runs from the installed home [SandboxNativeLibs] gives it, where the same
 * file unpacked into the app's storage is refused (reported: `error=13, Permission denied` from a C# plugin's
 * compiler in the sandbox). The "plugin" packages the IDE's own aapt2, a real Android executable.
 *
 * The first install asks; on an emulator, accept Android's prompt (or tap it from adb) while this runs.
 */
@RunWith(AndroidJUnit4::class)
class SandboxNativeLibsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pluginApk(): File {
        val abi = Build.SUPPORTED_ABIS.first()
        val program = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        val apk = File(SandboxProcess.stateDir(context), "plugin-probe.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/$abi/libprobe.so")); program.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            // A JNI-library-shaped passenger, so each run is a new set of libraries (and a new install).
            zip.putNextEntry(ZipEntry("lib/$abi/libstamp.so")); zip.write(System.nanoTime().toString().toByteArray()); zip.closeEntry()
        }
        return apk
    }

    private fun run(program: File): String {
        val p = ProcessBuilder(program.absolutePath, "version").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    @Test
    fun theUnpackedCopyCannotRun() {
        val copy = File(SandboxProcess.stateDir(context), "probe-unpacked/libprobe.so").apply { parentFile!!.mkdirs() }
        File(context.applicationInfo.nativeLibraryDir, "libaapt2.so").copyTo(copy, overwrite = true)
        copy.setExecutable(true, true)
        try {
            run(copy)
            fail("Android 10+ should refuse to run a program from the app's own storage")
        } catch (e: IOException) {
            assertTrue(e.toString(), "error=13" in e.toString())
        }
    }

    @Test
    fun aPackagedProgramRunsFromTheInstalledHome() {
        val apk = pluginApk()
        val said = mutableListOf<String>()
        val installed = SandboxNativeLibs.ensureInstalled(context, apk) { said += it }
        assertTrue(said.joinToString("\n"), installed)
        val dir = requireNotNull(SandboxNativeLibs.installedDir(context, apk)) { "installed and visible to this app" }
        val out = run(dir.resolve("libprobe.so").toFile())
        assertTrue(out, "Android Asset Packaging Tool" in out)
        // A second Run of the same build installs nothing.
        val again = mutableListOf<String>()
        assertTrue(SandboxNativeLibs.ensureInstalled(context, apk) { again += it })
        assertTrue(again.toString(), again.isEmpty())
    }
}
