package dev.ide.preview.realview

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import dev.ide.android.DexPeerFactory
import dev.ide.jvm.PeerFactory
import dev.ide.platform.log.Log
import dev.ide.preview.impl.RealViewRequest
import dev.ide.preview.impl.RealViewResult
import dev.ide.preview.impl.RealViewRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import androidx.core.graphics.createBitmap
import dev.ide.android.support.tasks.SharedLibraryDexer

/**
 * On-device [RealViewRuntime] — the "layoutlib-on-device" preview. Reuses the host-relinked aapt2
 * `resources.ap_` (real arsc + compiled layout XML) and the build's `R.jar` (ids matching the arsc): it builds
 * a real `Context`/`Resources` over the linked resources ([ResourceContextFactory]), inflates the layout with
 * the framework's real `LayoutInflater`, and draws the resulting real view tree to a [Bitmap].
 *
 * Non-framework view classes (library and project custom views) are INTERPRETED from their compiled bytecode
 * by a [VmViewFactory] over the bytecode VM — nothing is dexed and no `DexClassLoader` loads the project's or
 * the libraries' code, which is what keeps the preview clear of Google Play's dynamic-code policy. The
 * [VmViewFactory] and the [ResourceContextFactory.PreviewContext] are CACHED across renders (keyed by the
 * classpath signature and by the linked resources + theme/density/night), so a per-edit render is just inflate
 * + measure + draw. The rendered [Bitmap] is returned live ([RealViewResult.nativeBitmap]) for the same-process
 * UI to wrap with no PNG encode/decode. Every failure is returned as [RealViewResult.error] (never thrown), so
 * the caller falls back to owned rendering.
 */
class AndroidRealViewRuntime(
    private val context: Context,
    // Retained for the preview-service constructor contract; the interpret-only path no longer dexes, so the
    // SDK jar / device API level / shared dex cache are unused here.
    @Suppress("unused") private val androidJar: Path,
    private val cacheDir: File,
    @Suppress("unused") private val deviceApiLevel: Int,
    @Suppress("unused") private val dexCacheRoot: Path? = null,
) : RealViewRuntime {

    private val log = Log.logger("ide.preview")
    private val lock = Any()
    private var contextCache: Pair<String, ResourceContextFactory.PreviewContext>? = null
    private var vmFactoryCache: Pair<String, VmViewFactory>? = null

    // One peer factory for the runtime's lifetime, shared across VM rebuilds so a generated peer class (an
    // interpreted view's real subclass) is dexed + defined ONCE and reused when the classpath changes but the
    // peer shape doesn't. Interpret mode only; built lazily so the dex-mode path never touches it.
    private val peerFactory: PeerFactory by lazy { DexPeerFactory() }

    /** Root of the real-view dex cache; the `.hashcache` sidecar under it is shared by [classpathSig] and the
     *  library dexer, so a jar is content-hashed once per (path, size, mtime) across both the signature and the dex. */
    private val realviewDexRoot = File(cacheDir, "realview-dex")
    private val hashCacheDir = File(realviewDexRoot, "hashcache")

    // Real views start animators on inflate/attach (indeterminate ProgressBar, Material progress/refresh, …),
    // and `Animator.start()` throws "Animators may only be run on Looper threads" off a Looper thread. render()
    // is called on a Binder thread (in :preview) or a background dispatcher (in-process fallback), neither of
    // which has a Looper — so the inflate/measure/layout/draw runs on this dedicated Looper thread instead.
    private val renderThread by lazy { HandlerThread("realview-render").apply { start() } }
    private val renderHandler by lazy { Handler(renderThread.looper) }

    override fun render(request: RealViewRequest): RealViewResult = onLooperThread {
        val resourcesAp = request.resourcesAp.toFile()
        if (!resourcesAp.exists()) RealViewResult(
            null, error = "resources.ap_ missing — build the project first"
        )
        else runCatching { renderInternal(request, resourcesAp) }.getOrElse { t ->
            // Surface the WHOLE cause chain: an InflateException's message is just "Error inflating class
            // <unknown>" — the real reason (a Material theme-enforcement IllegalArgumentException, a
            // NoClassDef, etc.) is the cause. Log the full stack too, for the diagnostic path.
            log.warn("real-view render failed: ${t.stackTraceToString()}")
            RealViewResult(null, error = causeChain(t))
        }
    }

    /** Flatten [t]'s cause chain into one line ("Outer: msg  ← Inner: msg  ← Root: msg"), so the status chip
     *  shows the ROOT reason instead of just the top-level InflateException's "<unknown>". */
    private fun causeChain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (depth > 0) sb.append("  ← ")
            sb.append(cur.javaClass.simpleName).append(": ").append(cur.message ?: "")
            val next = cur.cause
            if (next === cur) break
            cur = next
            depth++
        }
        return sb.toString().trim()
    }

    /** Run [block] on the render thread (which has a Looper, so view animators don't throw), blocking the
     *  caller for the result. Runs inline if the caller already has a Looper. */
    private fun onLooperThread(block: () -> RealViewResult): RealViewResult {
        if (Looper.myLooper() != null) return block()
        val holder = arrayOfNulls<RealViewResult>(1)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                holder[0] = block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return holder[0] ?: RealViewResult(null, error = "render produced no result")
    }

    private fun renderInternal(request: RealViewRequest, resourcesAp: File): RealViewResult {
        val notify = request.stageListener
        val t0 = System.nanoTime()
        // The classpath is INTERPRETED by the bytecode VM — nothing is dexed or loaded into ART (no
        // DexClassLoader), which is what keeps the preview clear of Google Play's dynamic-code policy. The
        // context classloader is the framework boot loader (android.*/java.* only); every non-framework tag is
        // created by the VM view factory below.
        val jars = request.classpath.filter { it.toString().endsWith(".jar") }
        val dirs = request.classpath.filter { Files.isDirectory(it) }
        val sig: String
        synchronized(lock) {
            sig = "${classpathSig(jars)}|dirs:${classDirSig(dirs)}|api${request.minApi}"
        }
        val vmFactory: VmViewFactory = cachedVmFactory(request.classpath, sig, notify)
        val classLoader: ClassLoader = View::class.java.classLoader ?: javaClass.classLoader!!
        val tLoader = System.nanoTime()
        val pc = cachedContext(request, resourcesAp, classLoader, sig, vmFactory)
        val tCtx = System.nanoTime()
        val ctx = pc.context
        val layoutId =
            ctx.resources.getIdentifier(request.layoutName, "layout", request.packageName)
        if (layoutId == 0) return RealViewResult(
            null, error = "layout '${request.layoutName}' not found in linked resources"
        )

        val w = request.widthPx.coerceAtLeast(1)
        val h = request.heightPx.coerceAtLeast(1)

        // Inflate into a REAL PhoneWindow decor so the theme's window background + action bar render like an
        // actual activity window (the window decor the framework builds in setContentView), not just the bare
        // layout content. The PhoneWindow is obtained via a Dialog — public API, so no hidden internal class
        // (hidden-API exemptions are unreliable on device). Falls back to inflating the content alone if the
        // windowed path isn't usable here.
        notify?.invoke("Inflating")
        val stepsBefore = vmFactory?.steps ?: 0L
        var usedDecor = true
        var decorError: String? = null
        val root = runCatching { decorView(ctx, request, layoutId) }.getOrElse {
            usedDecor = false; decorError = "${it.javaClass.simpleName}: ${it.message ?: ""}"
            // Fallback: inflate through the PREVIEW context's inflater (which carries the VM view factory via
            // getSystemService), NOT the app context's — the app inflater has no factory, so interpreted
            // library/user views wouldn't be created and would fail to load from the boot class loader.
            LayoutInflater.from(ctx).cloneInContext(ctx).inflate(layoutId, null, false)
        }

        // Synthesize system-bar insets (status + nav) so `fitsSystemWindows` / inset-driven padding apply, as
        // they would under a real window (there's no WindowManager here to deliver them).
        applyWindowInsets(ctx, root)

        // An activity window FILLS its bounds — measure EXACTLY so a `match_parent` root (e.g. CoordinatorLayout)
        // fills the device height. AT_MOST would let the root wrap its content, collapsing the layout to the top.
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        val tInflate = System.nanoTime()

        notify?.invoke("Drawing")
        val bmp = createBitmap(w, h)
        root.draw(Canvas(bmp))
        val tDraw = System.nanoTime()

        // Snapshot the inflated tree (class/id/bounds/attributes) for the Preview view's hierarchy +
        // tap-to-inspect panels. Best-effort — a capture failure must not fail an otherwise-good render.
        val viewTree = runCatching { ViewHierarchyCapture.capture(root) }.getOrElse {
            log.warn("real-view hierarchy capture failed: ${it.message}"); null
        }

        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        log.info("real-view '${request.layoutName}' ${w}x$h decor=$usedDecor${decorError?.let { " (decor failed: $it)" } ?: ""} " + "| classloader=${
            ms(
                t0,
                tLoader
            )
        }ms context=${ms(tLoader, tCtx)}ms inflate=${
            ms(
                tCtx, tInflate
            )
        }ms draw=${ms(tInflate, tDraw)}ms${vmFactory?.let { " interpret-steps=${it.steps - stepsBefore}" } ?: ""}")

        return RealViewResult(
            pngBytes = null, width = w, height = h, nativeBitmap = bmp, viewTree = viewTree
        )
    }

    /**
     * Build a real window decor for [layoutId] via a [Dialog] (which constructs a `PhoneWindow` through public
     * API). `setContentView` makes the framework install the theme's decor — window background + action bar +
     * title bar per the activity theme — and inflate the layout into it; we never `show()` it (no window token
     * is attached), just draw the decor tree. The dialog uses the project's activity theme so the decor matches.
     */
    private fun decorView(ctx: Context, request: RealViewRequest, layoutId: Int): View {
        // Resolved with the Material/AppCompat fallback ladder — a bare Dialog theme makes any
        // Material/AppCompat widget throw its theme-enforcement error and fail the whole render.
        val themeResId = PreviewThemes.resolve(ctx.resources, request.packageName, request.themeName)
        val dialog = if (themeResId != 0) Dialog(ctx, themeResId) else Dialog(ctx)
        dialog.setContentView(layoutId)
        return dialog.window?.decorView ?: error("Dialog produced no decor view")
    }

    /** Dispatch synthesized status + navigation bar insets to [root] before measuring (API 30+). */
    private fun applyWindowInsets(ctx: Context, root: View) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val insets = WindowInsets.Builder().setInsets(
                WindowInsets.Type.systemBars(),
                Insets.of(
                    0,
                    systemDimenPx(ctx, "status_bar_height", 24),
                    0,
                    systemDimenPx(ctx, "navigation_bar_height", 48)
                ),
            ).build()
            root.dispatchApplyWindowInsets(insets)
        }
    }

    /** A framework dimen (e.g. `status_bar_height`) in px, or [defaultDp] converted to px if unavailable. */
    private fun systemDimenPx(ctx: Context, name: String, defaultDp: Int): Int {
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) ctx.resources.getDimensionPixelSize(id)
        else (defaultDp * ctx.resources.displayMetrics.density).toInt()
    }

    /** The cached preview [Context]/[android.content.res.Resources]; rebuilt only when the linked resources, theme, density, night,
     *  or classpath change. The previous one is closed on replacement (it holds a loader/file descriptor). In
     *  interpret mode [vmFactory] is installed as the context's inflater factory so non-framework tags are
     *  created by the VM (the factory instance is keyed by the same [sig], so context + factory rebuild together). */
    private fun cachedContext(
        request: RealViewRequest, resourcesAp: File, classLoader: ClassLoader, sig: String,
        vmFactory: VmViewFactory?,
    ): ResourceContextFactory.PreviewContext = synchronized(lock) {
        val key =
            "${resourcesAp.lastModified()}|${resourcesAp.length()}|${request.packageName}|${request.themeName}|${request.density}|${request.night}|$sig"
        contextCache?.let { (k, pc) -> if (k == key) return pc else runCatching { pc.close() } }
        ResourceContextFactory.create(
            context,
            resourcesAp,
            classLoader,
            request.packageName,
            request.themeName,
            request.density,
            request.night,
            inflaterFactory = vmFactory,
        ).also { contextCache = key to it }
    }

    /** The cached [VmViewFactory] over the interpret-mode classpath, keyed by [sig]; the previous one is closed
     *  on replacement (it holds open library-jar handles). Reports "Interpreting" when a rebuild happens. */
    private fun cachedVmFactory(classpath: List<Path>, sig: String, notify: ((String) -> Unit)?): VmViewFactory =
        synchronized(lock) {
            vmFactoryCache?.takeIf { it.first == sig }?.second ?: run {
                notify?.invoke("Interpreting")
                vmFactoryCache?.second?.let { old -> runCatching { old.close() } }
                VmViewFactory(classpath, peerFactory).also { vmFactoryCache = sig to it }
            }
        }

    /** A content signature for the interpret-mode project class DIRECTORIES (the build's compiled `.class`
     *  output). Walks each dir's `.class` files hashing relative path + size + mtime, so a rebuild that changes
     *  a class flips the signature (rebuilding the VM factory) while a no-op render reuses it. NOT thread-safe
     *  in isolation — callers hold [lock] (paired with [classpathSig]'s shared sidecar). */
    private fun classDirSig(dirs: List<Path>): String {
        if (dirs.isEmpty()) return "0"
        val sb = StringBuilder()
        for (dir in dirs.filter { Files.isDirectory(it) }.sorted()) {
            runCatching {
                Files.walk(dir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                        .sorted()
                        .forEach { p ->
                            sb.append(dir.relativize(p)).append(':')
                                .append(Files.size(p)).append(':')
                                .append(Files.getLastModifiedTime(p).toMillis()).append('|')
                        }
                }
            }
        }
        return sb.toString().hashCode().toString()
    }

    /** A CONTENT signature for a set of library jars — computed separately for the library
     *  layer and the R layer (see [renderInternal]) to key the disk dex cache, the in-memory loader cache, and the
     *  preview context cache. Content-based (via [SharedLibraryDexer.contentHashes], the same hashing the dexer
     *  uses), NOT mtime: the build regenerates `R.jar` and the module-output jars on every build, so an mtime key
     *  flipped every build and forced a full re-merge of the whole classpath into `classes*.dex` even when those
     *  jars were byte-identical. A content hash reuses the merged dex + loader unless a jar's contents actually
     *  changed. The `.hashcache` sidecar keeps this cheap on an identical rebuild — only the
     *  mtime-changed files are re-hashed (to the SAME value), the rest are sidecar hits. Order-independent (the
     *  DexIndexed merge renumbers a class set); path is kept per entry so add/remove/replace still flips it.
     *  NOT thread-safe (shared sidecar) — callers hold [lock]. */
    private fun classpathSig(classpath: List<Path>): String {
        val jars =
            classpath.filter { it.toString().endsWith(".jar") }
        val hashes = SharedLibraryDexer.contentHashes(
            jars, hashCacheDir.toPath()
        )
        return jars.mapNotNull { j -> hashes[j]?.let { "$j:$it" } }.sorted().joinToString("|")
            .hashCode().toString()
    }
}
