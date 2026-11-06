package dev.codeassist.dart

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ide.plugin.ui.EditorPreviewContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which files the Flutter preview pane is for: a `.dart` file of a Flutter package that declares a `@Preview`.
 * Asked during composition, so the answer is kept per file until the file changes.
 */
internal object FlutterPreviewFiles {
    private class Answer(val stamp: Long, val size: Long, val applies: Boolean)

    private val answers = ConcurrentHashMap<String, Answer>()

    fun appliesTo(path: String): Boolean {
        if (!path.endsWith(".dart")) return false
        val file = Paths.get(path)
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: return false
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        answers[path]?.let { if (it.stamp == stamp && it.size == size) return it.applies }
        val applies = runCatching {
            String(Files.readAllBytes(file)).contains("@Preview") && packageOf(file)?.let(FlutterSdk::isFlutterPackage) == true
        }.getOrDefault(false)
        answers[path] = Answer(stamp, size, applies)
        return applies
    }

    fun packageOf(file: Path): Path? = DartPackages.packageRootOf(file, file.root ?: return null)
}

/** One package's preview: the gallery's compiler and the engine it runs in, outliving the pane that shows them. */
@RequiresApi(30)
internal class FlutterPreviewSession(val pkg: Path, tools: DartPlugin.Tools) {
    val controller = FlutterPreviewController(pkg, tools)
    val engine = FlutterPreviewEngine(controller)
    internal var refs = 0
    internal var parkedAt = 0L

    init {
        val main = Handler(Looper.getMainLooper())
        controller.onPause = { paused -> main.post { engine.pause(paused) } }
    }

    fun close() {
        engine.close()
        controller.close()
    }
}

/**
 * The previews panes are showing, and the ones a pane let go of a moment ago.
 *
 * Switching between Split and Preview, or away from the file and back, recreates the pane. The session is
 * not recreated with it: the new pane takes over the one the old pane showed, its compiler and its running
 * engine, so the previews carry on exactly as they were, state and scroll position included. Two panes on one
 * package share a session; the one that attached last shows it.
 *
 * What no pane shows is let go in two steps, the engine first: after [ENGINE_KEEP_MS] the engine (and with it the
 * preview process), after [KEEP_MS] the compiler too. A pane back in between gets a new engine and the previews
 * restarted in it, without a compile.
 */
@RequiresApi(30)
internal object FlutterPreviewSessions {
    private const val ENGINE_KEEP_MS = 15_000L
    private const val KEEP_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val sessions = HashMap<Path, FlutterPreviewSession>()

    fun acquire(pkg: Path, tools: DartPlugin.Tools): FlutterPreviewSession = synchronized(this) {
        sessions.getOrPut(pkg) { FlutterPreviewSession(pkg, tools) }.also { it.refs++ }
    }

    fun release(session: FlutterPreviewSession) {
        val parkedAt = synchronized(this) {
            session.refs--
            if (session.refs > 0) return
            System.nanoTime().also { session.parkedAt = it }
        }
        main.postDelayed({
            val idle = synchronized(this) { session.refs == 0 && session.parkedAt == parkedAt }
            if (idle) session.engine.close()
        }, ENGINE_KEEP_MS)
        main.postDelayed({
            val expired = synchronized(this) {
                if (session.refs == 0 && session.parkedAt == parkedAt && sessions[session.pkg] === session) {
                    sessions.remove(session.pkg)
                } else null
            }
            expired?.close()
        }, KEEP_MS)
    }
}

/**
 * The editor's Flutter preview: the open file's `@Preview` widgets, live, in the same pane as the built-in
 * previews. The Flutter engine runs in the plugin's own app and draws straight into this pane (see
 * [FlutterPreviewService]); the gallery it runs is compiled and hot-reloaded from here
 * ([FlutterPreviewController]). Android 11 and later; before that the "Preview widgets" Run shows the same
 * gallery in the player.
 */
@Composable
internal fun FlutterPreviewPane(ctx: EditorPreviewContext) {
    val tools = DartPlugin.tools
    val pkg = remember(ctx.path) { FlutterPreviewFiles.packageOf(Paths.get(ctx.path)) }
    if (Build.VERSION.SDK_INT < 30 || tools == null || pkg == null) {
        val reason = when {
            Build.VERSION.SDK_INT < 30 -> "The preview pane needs Android 11 or later. Run \"Preview widgets\" shows them in the player instead."
            tools == null -> "The Dart plugin's engine side did not start."
            else -> "This file is not in a pub package."
        }
        SideEffect { ctx.reportProblems(emptyList()) }
        Notice(reason)
        return
    }
    val session = remember(pkg) { FlutterPreviewSessions.acquire(pkg, tools) }
    DisposableEffect(session) { onDispose { FlutterPreviewSessions.release(session) } }
    LaunchedEffect(session, ctx.path, ctx.dark) { session.controller.show(ctx.path, ctx.dark) }
    val state by session.controller.state.collectAsState()
    SideEffect { ctx.reportProblems(state.problems) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> FlutterPreviewSurface(context, session.engine) },
            modifier = Modifier.fillMaxSize(),
        )
        state.message?.let { Notice(it) }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A session's engine in [FlutterPreviewService]: the binding, the engine's id there, and which pane surface
 * shows it now. It outlives surfaces. A surface that attaches asks for the engine; the service makes one the
 * first time and from then on hands the same running engine a new surface, so a recreated pane does not
 * restart the previews. Only [close] (the session expiring) ends the engine. All on the main thread.
 */
@RequiresApi(30)
internal class FlutterPreviewEngine(private val controller: FlutterPreviewController) {
    private val id = ids.incrementAndGet()
    private var service: Messenger? = null
    private var bound = false
    private var context: Context? = null
    private var surface: FlutterPreviewSurface? = null
    private var requested = false
    private var showing = false
    /** Paused for a Run of the package ([FlutterPreviewController.onPause]): no engine wanted until it ends. */
    private var paused = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            requested = false
            open()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Its process died, and the engine with it. Android binds it again by itself, and
            // onServiceConnected asks for a new engine, which the controller restarts the previews in.
            service = null
            requested = false
            showing = false
            controller.engineFailed("The preview engine stopped; starting it again ...")
        }
    }

    /** [view] is the pane's surface now (a newer pane's takes over from an older one). */
    fun attach(view: FlutterPreviewSurface) {
        surface = view
        requested = false
        showing = false
        if (!bound) {
            val ctx = view.context.applicationContext.also { context = it }
            val intent = Intent().setClassName(FlutterPlayer.PACKAGE, FlutterPreviewService.CLASS)
            bound = runCatching { ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT) }
                .getOrDefault(false)
            if (!bound) controller.engineFailed("The Dart plugin's preview service could not be reached.")
        }
        open()
    }

    /** Let the engine go while the preview is [paused] (a Run of the package is going), and ask for a new one after. */
    fun pause(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        if (paused) {
            send(FlutterPreviewService.CLOSE)
            requested = false
            showing = false
        } else {
            open()
        }
    }

    /** [view] left its window. The engine keeps running for whichever surface attaches next. */
    fun detach(view: FlutterPreviewSurface) {
        if (surface !== view) return
        surface = null
        requested = false
        showing = false
    }

    fun sized(view: FlutterPreviewSurface) {
        if (surface !== view) return
        if (requested) send(FlutterPreviewService.RESIZE, size(view)) else open()
    }

    /** Whether [view] is the surface the engine draws into, so its touches are the engine's. */
    fun shows(view: FlutterPreviewSurface): Boolean = surface === view && showing

    fun input(view: FlutterPreviewSurface, event: MotionEvent) {
        if (!shows(view)) return
        send(FlutterPreviewService.INPUT, Bundle().apply { putParcelable(FlutterPreviewService.KEY_EVENT, MotionEvent.obtain(event)) })
    }

    /**
     * End the engine and the binding (the preview process goes once nothing else holds it). A surface that attaches
     * afterwards binds again and gets a new engine.
     */
    fun close() {
        send(FlutterPreviewService.CLOSE)
        if (bound) runCatching { context?.unbindService(connection) }
        bound = false
        service = null
        requested = false
        showing = false
    }

    private fun open() {
        if (paused) return
        val view = surface ?: return
        if (requested || service == null || view.width <= 0 || view.height <= 0) return
        val token = view.hostToken ?: return
        val display = view.display ?: return
        requested = true
        send(FlutterPreviewService.OPEN, size(view).apply {
            putBinder(FlutterPreviewService.KEY_HOST_TOKEN, token)
            putInt(FlutterPreviewService.KEY_DISPLAY, display.displayId)
        })
    }

    private fun size(view: SurfaceView) = Bundle().apply {
        putInt(FlutterPreviewService.KEY_WIDTH, view.width)
        putInt(FlutterPreviewService.KEY_HEIGHT, view.height)
    }

    private fun onReply(msg: Message) {
        if (msg.arg1 != id) return
        when (msg.what) {
            FlutterPreviewService.OPENED -> {
                @Suppress("DEPRECATION")
                val pkg = msg.data.getParcelable<SurfaceControlViewHost.SurfacePackage>(FlutterPreviewService.KEY_SURFACE)
                val uri = msg.data.getString(FlutterPreviewService.KEY_URI)
                val view = surface
                if (pkg == null || uri == null) {
                    controller.engineFailed("The preview engine sent no surface.")
                    return
                }
                if (view == null) { pkg.release(); return }
                view.setChildSurfacePackage(pkg)
                showing = true
                // The size may have changed while the engine was starting.
                send(FlutterPreviewService.RESIZE, size(view))
                // The same engine handed to a new surface is already running the previews.
                if (!msg.data.getBoolean(FlutterPreviewService.KEY_REUSED)) {
                    controller.engineReady(uri, msg.data.getString(FlutterPreviewService.KEY_ISOLATE))
                }
            }
            FlutterPreviewService.FAILED ->
                controller.engineFailed(msg.data.getString(FlutterPreviewService.KEY_REASON) ?: "The preview engine did not start.")
        }
    }

    private fun send(what: Int, data: Bundle = Bundle()) {
        val to = service ?: return
        try {
            to.send(Message.obtain(null, what, id, 0).apply {
                this.data = data
                replyTo = replies
            })
        } catch (_: RemoteException) {
            // The service died; onServiceDisconnected follows.
        }
    }

    private companion object {
        val ids = AtomicInteger()
    }
}

/**
 * The pane's window into the preview engine: a [SurfaceView] whose child surface the plugin's process draws.
 * What it shows, and the binding behind it, belong to the session's [FlutterPreviewEngine]; this view only
 * says when it is attached and how big it is, and passes on what it is touched with.
 */
@SuppressLint("ViewConstructor")
@RequiresApi(30)
internal class FlutterPreviewSurface(context: Context, private val engine: FlutterPreviewEngine) : SurfaceView(context) {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.attach(this)
    }

    override fun onDetachedFromWindow() {
        engine.detach(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.sized(this)
    }

    // The IDE's window is above this surface, so its input arrives here; it is the embedded view's, so pass it on.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!engine.shows(this)) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        engine.input(this, event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!engine.shows(this) || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        engine.input(this, event)
        return true
    }
}
