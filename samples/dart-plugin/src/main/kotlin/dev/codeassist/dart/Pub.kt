package dev.codeassist.dart

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `dart pub`, run through the `dart` tool's own snapshot.
 *
 * It is started by [DartSdk.launcher], the `<sdk>/bin/dart` symlink, and not by the runtime's real path:
 * `dart` locates its SDK two directories above the path it was started by, and the real one is the native
 * library directory, which has no SDK around it. pub's `get`, `upgrade`, `add` and `outdated` work this way;
 * `dart run` and `dart test` do not (they look for compilers beside the runtime's resolved path), which is
 * why Run compiles and runs a program itself ([DartRun]).
 */
class Pub(private val sdk: DartSdk, private val flutter: FlutterSdk? = null) {

    /**
     * Run `dart pub <command> <args>` in [pkg], passing each line of its output to [out], and answer its exit
     * code, or -1 when it could not run or took longer than [timeoutSeconds]. Blocking.
     */
    fun run(
        pkg: Path,
        command: String,
        vararg args: String,
        timeoutSeconds: Long = 300,
        cancellation: ToolCancellation? = null,
        out: (String) -> Unit,
    ): Int {
        val status = sdk.prepare()
        if (status is DartSdk.Status.Unavailable) {
            out(status.reason)
            return -1
        }
        val dartdev = sdk.dartdev ?: return -1
        // A Flutter package's `flutter: sdk: flutter` dependencies resolve from FLUTTER_ROOT.
        val env = sdk.environment().toMutableMap()
        if (FlutterSdk.isFlutterPackage(pkg)) {
            when (val f = flutter?.prepare() ?: FlutterSdk.Status.Unavailable(FlutterSdk.NO_FLUTTER)) {
                is FlutterSdk.Status.Ready -> env["FLUTTER_ROOT"] = f.root.toString()
                is FlutterSdk.Status.Unavailable -> { out(f.reason); return -1 }
            }
        }
        val process = try {
            ProcessBuilder(listOf(sdk.launcher.toString(), dartdev.toString(), "--suppress-analytics", "pub", command) + args)
                .directory(pkg.toFile())
                .redirectErrorStream(true)
                .apply { environment().putAll(env) }
                .start()
        } catch (e: IOException) {
            out("Could not start pub: ${e.message}")
            return -1
        }
        cancellation?.attach(process)
        runCatching { process.outputStream.close() }
        // pub can wait on the network for a long time; the reader runs beside the timeout rather than in its way.
        val reader = Thread({
            try {
                process.inputStream.bufferedReader().forEachLine(out)
            } catch (_: IOException) {
                // Killed: the stream ended with the process.
            }
        }, "dart-pub-output").apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1_000)
            out("pub $command did not finish within $timeoutSeconds seconds")
            return -1
        }
        reader.join(5_000)
        return process.exitValue()
    }
}
