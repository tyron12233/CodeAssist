package dev.ide.desktop.run

import dev.ide.android.support.tools.AndroidSdkInstaller
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Finds adb. It is not bundled (Google ships platform-tools under the Android SDK license), so this looks where
 * an installed one lives: `ANDROID_HOME` / `ANDROID_SDK_ROOT`, the platform's default SDK folder (which is also
 * where the SDK Manager puts platform-tools when there is no SDK), then `PATH`. Checked on each call, a few file
 * stats, so an adb installed while the IDE runs is picked up without a restart.
 */
internal object AdbLocator {

    fun find(
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home").orEmpty(),
        os: String = System.getProperty("os.name").orEmpty(),
    ): Path? {
        val roots = buildList {
            env("ANDROID_HOME")?.let(::add)
            env("ANDROID_SDK_ROOT")?.let(::add)
            add(defaultSdkRoot(home, os, env).toString())
        }
        for (root in roots.filter { it.isNotBlank() }) {
            val adb = runCatching { AndroidSdkInstaller.adbIn(Paths.get(root, "platform-tools")) }.getOrNull()
            if (adb != null && Files.isExecutable(adb)) return adb
        }
        val names = if (os.lowercase().contains("win")) listOf("adb.exe") else listOf("adb")
        for (dir in env("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }) {
            for (name in names) {
                val adb = runCatching { Paths.get(dir, name) }.getOrNull() ?: continue
                if (Files.isRegularFile(adb) && Files.isExecutable(adb)) return adb
            }
        }
        return null
    }

    /** Where Android Studio puts the SDK, and where the SDK Manager installs when no SDK exists. */
    fun defaultSdkRoot(home: String, os: String, env: (String) -> String?): Path {
        val name = os.lowercase()
        return when {
            name.contains("mac") -> Paths.get(home, "Library", "Android", "sdk")
            name.contains("win") ->
                env("LOCALAPPDATA")?.let { Paths.get(it, "Android", "Sdk") } ?: Paths.get(home, "AppData", "Local", "Android", "Sdk")
            else -> Paths.get(home, "Android", "Sdk")
        }
    }
}

/** One line of `adb devices -l`: [state] is `device` when usable, else `unauthorized`, `offline`, … */
internal data class AdbDevice(val serial: String, val state: String, val model: String?) {
    val online: Boolean get() = state == "device"

    /** What a person calls it: the model (`Pixel 8`), marked when it is an emulator, else the serial. */
    val label: String
        get() {
            val name = model?.replace('_', ' ')?.takeIf { it.isNotBlank() } ?: serial
            return if (serial.startsWith("emulator-")) "$name (emulator)" else name
        }

    companion object {
        /** Parse `adb devices -l`. Skips the header and anything that is not a device row. */
        fun parse(output: String): List<AdbDevice> = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val fields = line.split(Regex("\\s+"))
                if (fields.size < 2) return@mapNotNull null
                val props = fields.drop(2).mapNotNull { f -> f.split(':', limit = 2).takeIf { it.size == 2 } }
                    .associate { it[0] to it[1] }
                AdbDevice(fields[0], fields[1], props["model"])
            }
            .toList()
    }
}

/** A finished adb call: its exit code and everything it printed (stdout and stderr together). */
internal class AdbResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Runs [adb] with arguments. Blocking; callers keep it off the UI thread. */
internal class Adb(val adb: Path) {

    fun run(vararg args: String, timeoutSeconds: Long = 60, onLine: (String) -> Unit = {}): AdbResult {
        val process = ProcessBuilder(listOf(adb.toString()) + args).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> out.appendLine(line); onLine(line) }
            }
        }, "adb-output").apply { isDaemon = true; start() }
        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // The caller was cancelled (a stopped test run): take the device-side command down with it.
            process.destroyForcibly()
            throw e
        }
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            return AdbResult(-1, out.append("adb did not finish within $timeoutSeconds s").toString())
        }
        reader.join(5_000)
        return AdbResult(process.exitValue(), out.toString())
    }

    fun devices(): List<AdbDevice> {
        // The first call also starts the adb server, which takes a moment.
        val r = run("devices", "-l", timeoutSeconds = 30)
        return if (r.ok) AdbDevice.parse(r.output) else emptyList()
    }
}
