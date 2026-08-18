package dev.ide.android.preview

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.IBinder
import android.os.Process
import android.view.MotionEvent
import dev.ide.android.DexPeerFactory
import dev.ide.awt.ToolkitWindows
import dev.ide.awt.Window
import dev.ide.awt.interp.AwtNameRemapper
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.VmInterruptedException
import dev.ide.jvm.interpretedConstructors
import dev.ide.jvm.interpretedMethods
import dev.ide.platform.log.Log
import dev.ide.swing.ToolkitEventQueue
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a Java AWT/Swing program in the `:preview` OS process and streams its window back to the IDE.
 *
 * The program is interpreted by the `:jvm-interp` VM with [AwtNameRemapper] pointing its `java.awt` and
 * `javax.swing` references at the owned `:awt-toolkit`, which is ordinary dexed app code here. Nothing on the
 * user's classpath is dexed or loaded into ART, and the toolkit needs no framework window: it paints through
 * an [dev.ide.preview.RCanvas], so a session is a [Bitmap], a [Canvas] over it, and a loop.
 *
 * **One thread owns the toolkit.** [Session.pump] is this toolkit's event-dispatch thread: it runs the
 * program's `main`, drains work posted with `SwingUtilities.invokeLater`, delivers the pointer events the IDE
 * forwarded, repaints when a window asked for it, and ends the run when the last window closes. Nothing else
 * ever touches the widget tree, which is why the toolkit itself needs no locking.
 *
 * The run is over when the program has no displayable window left, mirroring the desktop rule in
 * `VmProgramInterpreter`: a GUI program's `main` returns at `setVisible(true)` and the program lives on
 * afterwards.
 */
class SwingRunSessionService : Service() {

    private val log = Log.logger("ide.preview.swing")
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        sessions.values.toList().forEach { it.stop() }
        sessions.clear()
        super.onDestroy()
    }

    private val binder = object : ISwingRunSession.Stub() {

        override fun pid(): Int = Process.myPid()

        override fun open(
            classpath: Array<out String>?,
            mainClass: String?,
            args: Array<out String>?,
            widthPx: Int,
            heightPx: Int,
            frameDir: String?,
            cb: ISwingRunCallback?,
        ): Int {
            if (mainClass == null || frameDir == null || cb == null) return -1
            return runCatching {
                val id = nextId.incrementAndGet()
                val session = Session(
                    id = id,
                    classpath = (classpath ?: emptyArray()).map { Paths.get(it) },
                    mainClass = mainClass,
                    args = (args ?: emptyArray()).toList(),
                    width = widthPx,
                    height = heightPx,
                    frameDir = File(frameDir).apply { mkdirs() },
                    cb = cb,
                    onFinished = { sessions.remove(id) },
                )
                sessions[id] = session
                session.start()
                id
            }.getOrElse {
                log.warn("swing session failed to open", it)
                runCatching { cb.onError("${it.javaClass.simpleName}: ${it.message ?: ""}".trim()) }
                -1
            }
        }

        override fun dispatchPointer(sessionId: Int, action: Int, x: Float, y: Float, eventTimeMs: Long) {
            sessions[sessionId]?.postPointer(action, x, y)
        }

        override fun resize(sessionId: Int, widthPx: Int, heightPx: Int) {
            sessions[sessionId]?.postResize(widthPx, heightPx)
        }

        override fun close(sessionId: Int) {
            sessions.remove(sessionId)?.stop()
        }
    }

    /** One running program: its VM, its toolkit thread, and the frames it produces. */
    private inner class Session(
        private val id: Int,
        classpath: List<Path>,
        private val mainClass: String,
        private val args: List<String>,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val frameDir: File,
        private val cb: ISwingRunCallback,
        private val onFinished: () -> Unit,
    ) {
        private val seq = AtomicLong(0)
        private val pending = ConcurrentLinkedQueue<() -> Unit>()
        @Volatile private var running = true
        private var bitmap: Bitmap? = null
        private var canvas: AndroidCanvas? = null

        /** Measurement and paints for the whole run. Installed before `main` starts, because a program that
         *  calls `pack()` measures text while it is still building its UI, before the host sees any window. */
        private val graphics = AndroidGraphics()

        private val vm = Vm(
            source = classpathSource(classpath),
            policy = INTERPRET_USER_CODE,
            bridge = ReflectiveBridge(
                // A listener the program posted can fail after the frame it belonged to; report it into the
                // console rather than tearing down a run the user is still looking at.
                proxyExceptionSink = { t -> emit("\n${t.javaClass.simpleName}: ${t.message ?: ""}\n") },
            ),
            peerFactory = DexPeerFactory(),
        )

        private val thread = Thread(null, ::pump, "swing-run-$id", STACK_BYTES).apply { isDaemon = true }

        fun start() = thread.start()

        fun postPointer(action: Int, x: Float, y: Float) = pending.add { deliverPointer(action, x, y) }

        fun postResize(w: Int, h: Int) = pending.add {
            width = w
            height = h
            bitmap = null
            canvas = null
            ToolkitWindows.displayable().forEach { it.setSize(w, h) }
        }

        fun stop() {
            running = false
            vm.requestCancel()
            thread.interrupt()
        }

        /**
         * The toolkit's event-dispatch thread. Runs the program, then keeps its UI alive: drain posted work,
         * deliver forwarded input, repaint what asked for it, and stop when the last window closes.
         */
        private fun pump() {
            val savedOut = System.out
            val savedErr = System.err
            val programOut = PrintStream(ConsoleOut(), true, "UTF-8")
            System.setOut(programOut)
            System.setErr(programOut)
            var error: Throwable? = null
            ToolkitWindows.installedBackend = graphics
            try {
                runMain()
                while (running && ToolkitWindows.displayable().isNotEmpty()) {
                    drainPending()
                    ToolkitEventQueue.drain()
                    repaintDirtyWindows()
                    Thread.sleep(FRAME_INTERVAL_MS)
                }
            } catch (e: InterruptedException) {
                // Stop, or the service going away. Not a program failure.
            } catch (e: VmInterruptedException) {
                // The VM unwound because the run was cancelled. Also not a program failure.
            } catch (t: Throwable) {
                error = t
            } finally {
                System.setOut(savedOut)
                System.setErr(savedErr)
                ToolkitWindows.installedBackend = null
                ToolkitWindows.disposeAll()
                ToolkitEventQueue.clear()
                running = false
                onFinished()
                report(error)
            }
        }

        private fun runMain() {
            val methods = vm.interpretedMethods(mainClass).filter { it.name == "main" && !it.isConstructor }
            val static = methods.firstOrNull { it.isStatic && it.paramDescriptors == ARGS_DESC }
                ?: methods.firstOrNull { it.isStatic && it.paramDescriptors.isEmpty() }
            if (static != null) {
                static.invoke(null, if (static.paramDescriptors.isEmpty()) emptyList() else listOf(args.toTypedArray()))
                return
            }
            // The `class T { fun main() }` form: construct the class, then call its instance main.
            val instance = methods.firstOrNull { !it.isStatic }
                ?: throw IllegalStateException("no runnable main method on $mainClass")
            val ctor = vm.interpretedConstructors(mainClass).firstOrNull { it.paramDescriptors.isEmpty() }
                ?: throw IllegalStateException("no no-argument constructor to run instance main on $mainClass")
            instance.invoke(
                ctor.invoke(null, emptyList()),
                if (instance.paramDescriptors.isEmpty()) emptyList() else listOf(args.toTypedArray()),
            )
        }

        private fun drainPending() {
            while (true) (pending.poll() ?: return).invoke()
        }

        /** Deliver a forwarded pointer event to the frontmost window. */
        private fun deliverPointer(action: Int, x: Float, y: Float) {
            val window = ToolkitWindows.displayable().lastOrNull() ?: return
            // The toolkit models a click as press + release + click; only a completed tap is forwarded as one,
            // so ACTION_UP is the event that counts and MOVE/DOWN are absorbed until drag support exists.
            if (action == MotionEvent.ACTION_UP) window.click(x.toInt(), y.toInt())
        }

        private fun repaintDirtyWindows() {
            val window = ToolkitWindows.displayable().lastOrNull() ?: return
            if (!window.needsRepaint()) return
            pushFrame(window)
        }

        /** Paint [window] into the session bitmap and hand the pixels to the IDE. */
        private fun pushFrame(window: Window) {
            if (width <= 0 || height <= 0) return
            val target = bitmap ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmap = it
                canvas = AndroidCanvas(Canvas(it))
            }
            target.eraseColor(0)
            window.setSize(width, height)
            window.paintTo(canvas ?: return)

            val s = seq.incrementAndGet()
            val file = File(frameDir, "frame-$s.px")
            runCatching {
                val buffer = ByteBuffer.allocate(target.byteCount)
                target.copyPixelsToBuffer(buffer)
                file.outputStream().use { it.write(buffer.array()) }
                cb.onFrame(file.path, target.width, target.height, s)
            }.onFailure { log.warn("swing session $id frame push failed", it) }
        }

        private fun report(error: Throwable?) {
            runCatching {
                if (error == null) {
                    cb.onExited(0, "")
                } else {
                    val message = "${error.javaClass.simpleName}: ${error.message ?: ""}".trim()
                    log.warn("swing session $id failed", error)
                    cb.onExited(1, message)
                }
            }
        }

        private fun emit(text: String) {
            runCatching { cb.onOutput(text) }
        }

        /** The program's stdout, decoded and forwarded to the Run console. */
        private inner class ConsoleOut : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0) emit(String(b, off, len, Charsets.UTF_8))
            }
        }
    }

    /** Reads class bytes off the run classpath, remapping AWT and Swing names onto the toolkit as it goes. */
    private fun classpathSource(classpath: List<Path>): ClassBytesSource {
        val dirs = classpath.filter { Files.isDirectory(it) }
        val jars = classpath.filter { Files.isRegularFile(it) }
            .mapNotNull { runCatching { java.util.jar.JarFile(it.toFile()) }.getOrNull() }
        return ClassBytesSource { internalName ->
            val rel = "$internalName.class"
            val bytes = dirs.firstNotNullOfOrNull { d ->
                d.resolve(rel).takeIf { Files.isRegularFile(it) }?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
            } ?: jars.firstNotNullOfOrNull { jar ->
                jar.getJarEntry(rel)?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
            }
            bytes?.let { AwtNameRemapper.remap(it) }
        }
    }

    private companion object {
        val ARGS_DESC = listOf("[Ljava/lang/String;")

        /** Interpreted recursion runs on this thread's host stack, so give it the same headroom a console run gets. */
        const val STACK_BYTES = 16L * 1024 * 1024

        /** How long the pump waits between passes. A Swing UI repaints on events, not on a clock, so this only
         *  bounds how quickly a repaint request turns into a frame. */
        const val FRAME_INTERVAL_MS = 16L

        /**
         * Interpret the user's and the libraries' code; bridge the platform, the Kotlin runtime, and the owned
         * toolkit, which is real dexed app code the remapped program now points at.
         *
         * The toolkit prefixes are matched literally, so a user package that happened to start with
         * `dev.ide.awt.` would be bridged rather than interpreted. Colliding with the IDE's own package is
         * unlikely enough to accept, and the alternative (probing the app class loader per name) costs a
         * reflective lookup on every class the VM resolves.
         */
        val INTERPRET_USER_CODE = InterpretPolicy { name ->
            TOOLKIT_PREFIXES.none { name.startsWith(it) } && InterpretPolicy.DEFAULT.interpret(name)
        }

        val TOOLKIT_PREFIXES = listOf("dev/ide/awt/", "dev/ide/swing/", "dev/ide/preview/")
    }
}
