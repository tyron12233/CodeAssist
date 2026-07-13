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
import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.platform.log.Log
import dev.ide.preview.impl.RealViewRequest
import dev.ide.preview.impl.RealViewResult
import dev.ide.preview.impl.RealViewRuntime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import androidx.core.graphics.createBitmap

/**
 * On-device [RealViewRuntime] — the "layoutlib-on-device" preview. Reuses the host-relinked aapt2
 * `resources.ap_` (real arsc + compiled layout XML) and the build's `R.jar` (ids matching the arsc): it
 * D8-dexes the project's library jars + `R.jar`, loads them through a [DexClassLoader], builds a real
 * `Context`/`Resources` over the linked resources ([ResourceContextFactory]), inflates the layout with the
 * framework's real `LayoutInflater`, and draws the resulting real view tree to a [Bitmap].
 *
 * The [DexClassLoader] and the [ResourceContextFactory.PreviewContext] are CACHED across renders (keyed by the
 * classpath signature and by the linked resources + theme/density/night), so a per-edit render is just inflate
 * + measure + draw — the class-load and resource-context setup are not redone unless their inputs change. The
 * classpath is dexed in TWO layers so this stays cheap under layout iteration: the stable library jars are
 * merged into `classes*.dex` ONCE (re-merged only when a real library changes), while the app's volatile `R.jar`
 * (aapt2 reassigns ids on every resource edit) is dexed in its own layer — so a resource change re-dexes only R,
 * never the whole classpath. The rendered [Bitmap] is returned live ([RealViewResult.nativeBitmap]) for the
 * same-process UI to wrap with no PNG encode/decode. Every failure is returned as [RealViewResult.error] (never
 * thrown), so the caller falls back to owned rendering.
 */
class AndroidRealViewRuntime(
    private val context: Context,
    private val androidJar: Path,
    private val cacheDir: File,
    private val deviceApiLevel: Int,
    /** The build's shared content-addressed library-dex cache (`caches/dex`). When set, the preview dexes each
     *  library into the SAME cache the build uses (via [SharedLibraryDexer]) — so a library is D8-dexed once and
     *  reused across builds and previews. Null → per-preview dexing only (still cached locally). */
    private val dexCacheRoot: Path? = null,
) : RealViewRuntime {

    private val log = Log.logger("ide.preview")
    private val lock = Any()
    private var loaderCache: Pair<String, ClassLoader>? = null
    private var contextCache: Pair<String, ResourceContextFactory.PreviewContext>? = null

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
        // Split the classpath into three layers. The app's R.jar is VOLATILE (aapt2 reassigns resource ids on every
        // resource edit, so its content changes constantly); the library jars are STABLE; and the project's own code
        // arrives PRE-DEXED as `.dex` files (the build's `project-dex`/`lib-dex`). Each layer gets its own content
        // signature so a change to one re-does only that layer — an R edit re-dexes just R, not the whole classpath.
        val (rJars, nonR) = request.classpath.partition { isRJar(it) }
        val (projectDexes, libJars) = nonR.partition { it.toString().endsWith(".dex") }
        // Under lock: classpathSig reads/writes the shared `.hashcache` sidecar, and this serializes with the
        // dexing so a concurrent render either reuses the in-flight loader or waits for it (no torn sidecar).
        val librarySig: String
        val rSig: String
        val projectSig: String
        synchronized(lock) {
            librarySig = classpathSig(libJars) + "|api${request.minApi}"
            rSig = classpathSig(rJars) + "|api${request.minApi}"
            projectSig = projectDexSig(projectDexes) + "|api${request.minApi}"
        }
        val sig = "$librarySig|R:$rSig|P:$projectSig"
        val classLoader = cachedClassLoader(
            libJars, rJars, projectDexes, request.minApi, librarySig, rSig, projectSig, sig, notify
        )  // reports "Dexing" only when the library layer re-merges
        val tLoader = System.nanoTime()
        val pc = cachedContext(request, resourcesAp, classLoader, sig)
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
        var usedDecor = true
        var decorError: String? = null
        val root = runCatching { decorView(ctx, request, layoutId) }.getOrElse {
            usedDecor = false; decorError =
            "${it.javaClass.simpleName}: ${it.message ?: ""}"; LayoutInflater.from(context)
            .cloneInContext(ctx).inflate(layoutId, null, false)
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
        }ms draw=${ms(tInflate, tDraw)}ms")

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
        val themeResId = request.themeName?.substringAfterLast('/')
            ?.let { ctx.resources.getIdentifier(it, "style", request.packageName) } ?: 0
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

    /** The cached [DexClassLoader] over the merged library dex + the R dex + the project dex (keyed by [sig], the
     *  combination of [librarySig], [rSig] and [projectSig]); rebuilt only when one changes. Rebuilding on an R-only
     *  change is cheap: [buildClassLoader]'s library merge is skipped (its sig is unchanged) and only the tiny R dex
     *  is redone. [notify] reports "Dexing" only when the library layer actually re-merges. */
    private fun cachedClassLoader(
        libJars: List<Path>,
        rJars: List<Path>,
        projectDexes: List<Path>,
        minApi: Int,
        librarySig: String,
        rSig: String,
        projectSig: String,
        sig: String,
        notify: ((String) -> Unit)?,
    ): ClassLoader = synchronized(lock) {
        loaderCache?.takeIf { it.first == sig }?.second ?: buildClassLoader(
            libJars, rJars, projectDexes, minApi, librarySig, rSig, projectSig, notify,
        ).also { loaderCache = sig to it }
    }

    /** The cached preview [Context]/[android.content.res.Resources]; rebuilt only when the linked resources, theme, density, night,
     *  or classpath change. The previous one is closed on replacement (it holds a loader/file descriptor). */
    private fun cachedContext(
        request: RealViewRequest, resourcesAp: File, classLoader: ClassLoader, sig: String,
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
        ).also { contextCache = key to it }
    }

    /** A CONTENT signature for a set of jars (jars OR a prebuilt apk/dex) — computed separately for the library
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
            classpath.filter { it.toString().endsWith(".jar") || it.toString().endsWith(".apk") }
        val hashes = dev.ide.android.support.tasks.SharedLibraryDexer.contentHashes(
            jars, hashCacheDir.toPath()
        )
        return jars.mapNotNull { j -> hashes[j]?.let { "$j:$it" } }.sorted().joinToString("|")
            .hashCode().toString()
    }

    /** A CONTENT signature for the PRE-DEXED project layer (the build's `project-dex`/`lib-dex` `.dex` files). Uses
     *  the file bytes (a fast CRC32) so a rebuild that leaves the code byte-identical keeps the loader/staging cache;
     *  a real code change re-stages just this layer (a cheap copy — the `.dex` are already dexed). Keyed per path so
     *  add/remove flips it. */
    private fun projectDexSig(dexes: List<Path>): String =
        dexes.sorted().joinToString("|") { p ->
            val crc = runCatching { java.util.zip.CRC32().apply { update(Files.readAllBytes(p)) }.value }.getOrDefault(0L)
            "$p:$crc"
        }.hashCode().toString()

    /**
     * Load the classpath through a [DexClassLoader] built from TWO dex layers, so a resource edit doesn't
     * re-merge the whole classpath:
     *  - the STABLE library layer ([mergedLibraryDex]) — the library jars dexed via the shared [SharedLibraryDexer]
     *    cache (each library D8-dexed once, reused across builds and previews) and merged into indexed
     *    `classes*.dex`, gated by [librarySig]. This expensive merge re-runs only when a real library changes.
     *  - the VOLATILE R layer ([mergedRDex]) — the app's `R.jar` dexed alone, gated by [rSig]. `R` is nothing but
     *    resource-id constants, but aapt2 reassigns them on every resource edit so its content changes constantly;
     *    dexing it in its own layer means a resource edit re-dexes only R (milliseconds), not the classpath.
     * The dex files of all layers join the loader's dex path multidex-style — a class resolves from whichever layer
     * defines it (R classes from r-dex, library classes from the merged dex, project classes from the project dex),
     * so there is no collision even though the layers each write a `classes.dex`. Ordering matters only for the app's
     * own `R` (which the build may merge into the project dex): the R layer is placed BEFORE the project layer, so
     * the preview's own `preview-r.jar` (ids matching the relinked arsc) shadows any stale R baked into project-dex.
     */
    private fun buildClassLoader(
        libJars: List<Path>,
        rJars: List<Path>,
        projectDexes: List<Path>,
        minApi: Int,
        librarySig: String,
        rSig: String,
        projectSig: String,
        notify: ((String) -> Unit)?,
    ): ClassLoader {
        // Parent = the FRAMEWORK boot classloader (android.*/java.* only), NOT the IDE app classloader. The IDE
        // is itself a Compose app bundling androidx.*/kotlin.*; parenting to it makes the project's classes (from
        // the merged dex) resolve some androidx.* from the IDE's DIFFERENT versions → IncompatibleClassChangeError
        // (e.g. AppCompat's ResourceManagerInternal vs the IDE's androidx.collection.LruCache, "field count 8 vs
        // 9"). Isolating to boot means every non-framework class loads from the merged dex (the project's own
        // consistent versions).
        val parent: ClassLoader = View::class.java.classLoader ?: javaClass.classLoader!!
        val root = realviewDexRoot.apply { mkdirs() }
        val libDexDir = mergedLibraryDex(root, libJars, minApi, librarySig, notify)
        val rDexDir = mergedRDex(root, rJars, minApi, rSig)
        val projectDexDir = stagedProjectDex(root, projectDexes, projectSig)
        val dexes = buildList {
            libDexDir?.let { d ->
                d.walkTopDown().filter { it.extension == "dex" }.forEach { add(it.absolutePath) }
            }
            // R BEFORE the project layer, so the preview's R shadows any R merged into project-dex (see kdoc).
            rDexDir?.let { d ->
                d.walkTopDown().filter { it.extension == "dex" }.forEach { add(it.absolutePath) }
            }
            projectDexDir?.let { d ->
                d.walkTopDown().filter { it.extension == "dex" }.forEach { add(it.absolutePath) }
            }
        }
        if (dexes.isEmpty()) return parent
        val oat = File(root, "oat").apply { mkdirs() }
        return DexClassLoader(
            dexes.joinToString(File.pathSeparator), oat.absolutePath, null, parent
        )
    }

    /**
     * Stage the PRE-DEXED project layer under `<root>/project`, gated by [projectSig] (written to `project-sig.txt`).
     * The build already dexed the app's own code (Java + Kotlin) into `project-dex` (and dependency modules into
     * `lib-dex`); this only COPIES those `classes*.dex` in (no D8) — renumbered to a contiguous `classes*.dex` set so
     * two source dirs don't collide on a name — and clears their write bits (ART's W^X refuses a writable dex on a
     * [DexClassLoader]). Returns null when there are no project dex files (e.g. the project was never built). The
     * copy is cheap, so even a signature that flips on a no-op rebuild costs only a re-copy, not a re-dex/merge.
     */
    private fun stagedProjectDex(root: File, projectDexes: List<Path>, projectSig: String): File? {
        val dexes = projectDexes.filter { Files.exists(it) && it.toString().endsWith(".dex") }
        if (dexes.isEmpty()) return null
        val dir = File(root, "project")
        val sigFile = File(root, "project-sig.txt")
        val fresh = dir.exists() && sigFile.takeIf { it.exists() }?.readText() == projectSig &&
            (dir.listFiles()?.any { it.extension == "dex" } == true)
        if (!fresh) {
            dir.deleteRecursively(); dir.mkdirs()
            dexes.sorted().forEachIndexed { i, p ->
                val dst = File(dir, if (i == 0) "classes.dex" else "classes${i + 1}.dex")
                Files.copy(p, dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                dst.setWritable(false, false)  // ART W^X: a DexClassLoader refuses writable dex
            }
            sigFile.writeText(projectSig)
        }
        return dir
    }

    /**
     * The merged library `classes*.dex` under `<root>/out`, gated by [librarySig] (written to `sig.txt`). Uses the
     * SAME [SharedLibraryDexer] the build's `dexBuilder` uses, against the SAME content-addressed [dexCacheRoot]
     * (`caches/dex`) — so each library is D8-dexed ONCE and reused across builds and previews (whoever dexes it
     * first wins), per-library incremental. The per-class archives are then merged (D8 DexIndexed) into
     * `classes*.dex`; that merge is the expensive step, and gating it on [librarySig] (which EXCLUDES the volatile
     * R.jar) keeps it from re-running on a resource edit. Returns null when there are no library jars. [notify]
     * reports "Dexing" only when the merge actually runs (a disk-cache miss).
     */
    private fun mergedLibraryDex(
        root: File,
        libJars: List<Path>,
        minApi: Int,
        librarySig: String,
        notify: ((String) -> Unit)?
    ): File? {
        val jars = libJars.filter { Files.exists(it) && it.toString().endsWith(".jar") }
        if (jars.isEmpty()) return null
        val dexDir = File(root, "out")
        val sigFile = File(root, "sig.txt")
        val fresh = dexDir.exists() && sigFile.takeIf { it.exists() }
            ?.readText() == librarySig && (dexDir.listFiles()
            ?.any { it.extension == "dex" } == true)
        if (!fresh) {
            notify?.invoke("Dexing")  // reused from the shared cache when the build (or a prior preview) dexed these
            val dexer = D8InProcessDexer()
            // 1) Dex each library into the shared cache (or reuse the build's bucket), matching the build's key
            //    (minApi, debug, desugaring universe) so the buckets are shared.
            val libDexer = dev.ide.android.support.tasks.SharedLibraryDexer(
                dexer, androidJar, minApi, release = false, dexCacheRoot = dexCacheRoot,
                log = { log.info(it) },
            )
            val archiveRoot = File(root, "lib-archives").toPath()
            val universe = libDexer.computeUniverse(
                jars, hashCacheDir.toPath()
            )  // same sidecar classpathSig hashed into
            val scopeOk =
                kotlinx.coroutines.runBlocking { libDexer.dexScope(jars, archiveRoot, universe) }
            if (!scopeOk) error("preview library dexing failed")
            // 2) Merge the per-class archives into indexed classes*.dex. D8's DexIndexed merge takes the
            //    individual `.dex` FILES (not the bucket dirs — a dir is an "unsupported source file type"),
            //    exactly like the build's DexMergeTask (perBucketDexes.flatten()).
            dexDir.deleteRecursively(); dexDir.mkdirs()
            val dexFiles =
                archiveRoot.toFile().walkTopDown().filter { it.isFile && it.extension == "dex" }
                    .map { it.toPath() }.toList()
            if (dexFiles.isEmpty()) error("preview library dexing produced no .dex")
            val mr = dexer.dex(dexFiles, androidJar, minApi, false, dexDir.toPath())
            if (!mr.success) error(
                "preview dex merge failed: ${
                    mr.log.takeLast(2).joinToString(" / ").ifBlank { "(no diagnostics)" }
                }")
            // ART refuses to load writable dex (W^X) — clear write bits, like the custom-view dexer does.
            dexDir.walkTopDown().filter { it.extension == "dex" }
                .forEach { it.setWritable(false, false) }
            sigFile.writeText(librarySig)
        }
        return dexDir
    }

    /**
     * The app's `R.jar` dexed alone into `<root>/r-dex`, gated by [rSig] (written to `r-sig.txt`). R is tiny
     * (resource-id constant classes), so a plain D8 dex is instant — but its content changes on every resource
     * edit, which is exactly why it lives in its own layer instead of the merged library dex. Returns null when
     * there is no R.jar. The resulting `classes*.dex` joins the [DexClassLoader]'s dex path as an extra layer.
     */
    private fun mergedRDex(root: File, rJars: List<Path>, minApi: Int, rSig: String): File? {
        val jars = rJars.filter { Files.exists(it) && it.toString().endsWith(".jar") }
        if (jars.isEmpty()) return null
        val rDexDir = File(root, "r-dex")
        val rSigFile = File(root, "r-sig.txt")
        val fresh = rDexDir.exists() && rSigFile.takeIf { it.exists() }
            ?.readText() == rSig && (rDexDir.listFiles()?.any { it.extension == "dex" } == true)
        if (!fresh) {
            val dexer = D8InProcessDexer()
            rDexDir.deleteRecursively(); rDexDir.mkdirs()
            val mr = dexer.dex(jars, androidJar, minApi, false, rDexDir.toPath())
            if (!mr.success) error(
                "preview R.jar dex failed: ${
                    mr.log.takeLast(2).joinToString(" / ").ifBlank { "(no diagnostics)" }
                }")
            // ART refuses to load writable dex (W^X) — clear write bits, like the library layer does.
            rDexDir.walkTopDown().filter { it.extension == "dex" }
                .forEach { it.setWritable(false, false) }
            rSigFile.writeText(rSig)
        }
        return rDexDir
    }

    /** The app's aapt2 `R.jar` — resource-id constants only, regenerated whenever the resource set changes, so
     *  it's dexed in its own volatile layer out of the merged-library dex. Matches either the build's jar (AGP's
     *  `compile_and_runtime_not_namespaced_r_class_jar/R.jar`, see `AndroidBuildSystem.rJarPath`) by its parent
     *  dir, or the real-view preview's regenerated `preview-r.jar` (see `PreviewResourceLinker`) by name. */
    private fun isRJar(p: Path): Boolean =
        p.fileName?.toString() == "preview-r.jar" || p.parent?.fileName?.toString() == "compile_and_runtime_not_namespaced_r_class_jar"

    private companion object {
        /** D8's max supported API (a newer device level only warns); the preview dex is debug-only. */
        const val MAX_D8_API = 36
    }
}
