package dev.ide.android.preview

import android.content.Context
import dev.ide.build.engine.InterpretRunRequest
import dev.ide.build.engine.ProgramInterpreter
import dev.ide.build.engine.ProgramIo
import dev.ide.build.engine.RunWindow
import dev.ide.platform.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Sends a WINDOWED program to the `:preview` process and everything else to the ordinary console interpreter.
 *
 * On ART there is no `java.awt` and no `javax.swing`, so a Swing program run the console way dies at its first
 * `new JFrame()` with `ClassNotFoundException` (the bridge asks ART for a class no Android device has). Such a
 * program needs the owned toolkit, which lives behind [SwingRunSessionService], and it needs a surface, which
 * a console has none of. Its frames flow back through [ProgramIo.frame] to the Run screen, and taps flow the
 * other way through [ProgramIo.windowed].
 *
 * A console program is untouched: it keeps running on the in-process VM exactly as before.
 */
class SwingAwareProgramInterpreter(
    context: Context,
    private val console: ProgramInterpreter,
) : ProgramInterpreter {

    private val appContext = context.applicationContext
    private val client = SwingRunRemoteClient(appContext)
    private val log = Log.logger("ide.run.swing")

    override suspend fun run(request: InterpretRunRequest, io: ProgramIo): Int {
        if (!usesSwing(request.classpath)) return console.run(request, io)
        log.info("running ${request.mainClass} as a windowed program in :preview")
        return runWindowed(request, io)
    }

    private suspend fun runWindowed(request: InterpretRunRequest, io: ProgramIo): Int = withContext(Dispatchers.IO) {
        val frameDir = File(appContext.cacheDir, "swing-run/${System.nanoTime()}")
        val exit = CompletableDeferred<Int>()
        val host = object : SwingRunRemoteClient.Host {
            override fun onFrame(bitmap: android.graphics.Bitmap, seq: Long) = Unit
            override fun onOutput(text: String) = io.stdout(text)
            override fun onExited(exitCode: Int, error: String) {
                if (error.isNotEmpty()) io.stdout("\n$error\n")
                exit.complete(exitCode)
            }
        }
        // This run's screen is in the IDE process, so the frame is forwarded as the file it arrived in and
        // nothing here ever touches the pixels.
        val session = client.start(
            classpath = request.classpath.map { it.toString() },
            mainClass = request.mainClass,
            args = request.args,
            // A starting size only: the Run pane reports its own the moment it is laid out, and the program
            // is re-laid-out at that size, so nothing is ever scaled in the steady state.
            widthPx = SURFACE_WIDTH,
            heightPx = SURFACE_HEIGHT,
            frameDir = frameDir,
            host = host,
            rawFrames = { path, w, h, seq -> io.frame(path, w, h, seq) },
        ) ?: run {
            io.stdout("Could not start the preview process to show this program's window.\n")
            return@withContext 1
        }

        io.started()
        io.windowed(object : RunWindow {
            override fun pointer(action: Int, x: Float, y: Float) = session.pointer(action, x, y)
            override fun key(action: Int, keyCode: Int, keyChar: Char) = session.key(action, keyCode, keyChar)
            override fun scroll(x: Float, y: Float, notches: Int) = session.scroll(x, y, notches)
            override fun resize(widthPx: Int, heightPx: Int) = session.resize(widthPx, heightPx)
        })
        try {
            val code = exit.await()
            io.exited(code)
            code
        } finally {
            session.stop()
            runCatching { frameDir.deleteRecursively() }
        }
    }

    /**
     * Whether anything on the run classpath mentions AWT or Swing.
     *
     * A byte scan of the class files rather than a parse: the names are ASCII in the constant pool's modified
     * UTF-8, so a substring search finds every reference, costs no dependency, and is fast enough on a module's
     * own output. It errs toward the windowed path, which is the safe direction: a console program that somehow
     * mentions `javax/swing` simply opens no window and ends when `main` returns.
     */
    private fun usesSwing(classpath: List<Path>): Boolean = classpath.any { entry ->
        when {
            Files.isDirectory(entry) -> entry.toFile().walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .any { mentionsSwing(runCatching { it.readBytes() }.getOrNull()) }
            entry.toString().endsWith(".class") -> mentionsSwing(runCatching { Files.readAllBytes(entry) }.getOrNull())
            // Library jars are not scanned: a program is windowed because ITS OWN code opens a window, and a
            // dependency that merely bundles Swing classes says nothing about that.
            else -> false
        }
    }

    private fun mentionsSwing(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val text = String(bytes, Charsets.ISO_8859_1)
        return MARKERS.any { it in text }
    }

    private companion object {
        val MARKERS = listOf("javax/swing/", "java/awt/")

        /** The size a program's window is painted at until the Run pane reports its own. */
        const val SURFACE_WIDTH = 720
        const val SURFACE_HEIGHT = 1080
    }
}
