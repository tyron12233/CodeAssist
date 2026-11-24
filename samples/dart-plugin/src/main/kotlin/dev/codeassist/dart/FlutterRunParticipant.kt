package dev.codeassist.dart

import dev.ide.build.AndroidRunParticipant
import dev.ide.build.ProgramIo
import dev.ide.build.RunConsole
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.model.Module
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the Run of a Flutter app's Android module what `flutter run` is: the app's own debug APK, with its
 * plugins, hot-reloading as its Dart files are saved. The host builds, installs and launches the APK; the launch
 * asks the debug engine for a VM service on a port picked here, with no auth code, so the run can find it; then
 * the run attaches the same compiler, file watcher and r/R/q console the player uses ([FlutterRunTask.attach]).
 *
 * Release builds are left alone: their engine is ahead-of-time compiled, with no VM service to attach to.
 */
class FlutterRunParticipant(
    private val sdk: DartSdk,
    private val flutter: FlutterSdk,
    private val pub: Pub,
) : AndroidRunParticipant {

    override val consoleTitle = "Flutter"

    /** The VM service port each module's current run asked for, from [launchExtras] to [afterLaunch]. */
    private val ports = ConcurrentHashMap<String, Int>()

    override fun launchExtras(module: Module, variant: String): Map<String, Any>? {
        module.facets.get(FlutterFacet.KEY) ?: return null
        if (FlutterBuildPlugin.isRelease(variant)) return null
        val port = freePort()
        ports[module.name] = port
        return extras(port)
    }

    override suspend fun afterLaunch(module: Module, variant: String, io: ProgramIo, log: (String) -> Unit) {
        val facet = module.facets.get(FlutterFacet.KEY) ?: return
        val port = ports.remove(module.name) ?: return
        val pkg = Paths.get(module.dir.path).resolve(facet.source).normalize()
        // The APK was compiled with the app's Dart plugin registrant; a restart's kernel has to carry it too, or the
        // plugins' Dart side stops registering after the first `R`.
        val registrant = FlutterPlugins.dartRegistrantFile(pkg).takeIf { Files.isRegularFile(it) }
            ?.let { FlutterPlugins.dartRegistrantArgs(it) }.orEmpty()
        val task = FlutterRunTask(
            TaskName(":${module.name}:flutterRun"), pkg, sdk, flutter, pub, RunConsole(consoleTitle),
        )
        val result = task.attach(io, serviceUri(port), registrant)
        if (result is TaskResult.Failed) log(result.message)
    }

    private fun freePort(): Int = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: IOException) {
        // Any port: the engine picks another if it is taken, and the run then says it could not connect.
        40_000 + (System.nanoTime() % 20_000).toInt()
    }

    internal companion object {
        const val VM_SERVICE_PORT = "vm-service-port"
        const val DISABLE_AUTH_CODES = "disable-service-auth-codes"

        /**
         * The launch extras asking the debug engine for a VM service on [port] with no auth code: the keys
         * FlutterActivity reads into the engine's shell arguments (FlutterShellArgs), as `flutter run` passes
         * them with `am start`.
         */
        fun extras(port: Int): Map<String, Any> = mapOf(VM_SERVICE_PORT to port, DISABLE_AUTH_CODES to true)

        /** The VM service's HTTP root on [port]; VmService opens its WebSocket under it (`ws`). */
        fun serviceUri(port: Int): String = "http://127.0.0.1:$port/"
    }
}
