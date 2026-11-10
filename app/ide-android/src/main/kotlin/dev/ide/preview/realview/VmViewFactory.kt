package dev.ide.preview.realview

import android.content.Context
import android.util.AttributeSet
import android.view.InflateException
import android.view.LayoutInflater
import android.view.View
import dev.ide.jvm.PeerFailurePolicy
import dev.ide.jvm.VmStepBudgetException
import dev.ide.jvm.ClassAlias
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.hasInterpretedClass
import dev.ide.jvm.interpretedConstructors
import dev.ide.jvm.interpretedMethods
import dev.ide.jvm.isInterpretedInstanceOf
import dev.ide.platform.log.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Creates a layout's non-framework views by INTERPRETING their bytecode with the `:jvm-interp` VM — the
 * real-view preview's replacement for dexing the classpath into a `DexClassLoader`. Library view classes run
 * straight from the project's jars and the project's own custom views from the build's compiled `.class`
 * output; nothing is ever loaded by ART. The framework floor (everything the BOOT classloader has:
 * `android.*`, `java.*`) is bridged to the real classes — probing boot rather than the IDE app's classloader
 * mirrors the dex path's boot-parent choice, so interpreted `androidx.*` never mixes with the IDE's own
 * (differently versioned) bundled copies.
 *
 * An interpreted view crosses into the framework as a generated PEER: a real subclass of its nearest framework
 * base (`View`, `TextView`, …) whose overridden methods (`onMeasure`/`onDraw`/…) dispatch back into the
 * interpreter, produced by the shared [PeerFactory] (dex-defining on device). The framework's `LayoutInflater`
 * consults this as its [LayoutInflater.Factory2] for every tag, so it holds, measures, lays out, and draws the
 * peer like any other view; returning null defers a tag (framework widgets, boot-loadable names) to the
 * default inflation path.
 *
 * Bridged (run real, not interpreted): the platform (`android.*`/`java.*`, anything the boot loader has) and the
 * Kotlin language runtime (`kotlin.*`/`kotlinx.*`, the app's bundled stdlib — a stable ABI, and interpreting the
 * intrinsics on every call is pure tax). Interpreted from the project jars: the actual view code — `androidx.*`,
 * `com.google.android.material.*`, other libraries, and the user's own views — so it runs at the project's own
 * versions with nothing loaded into ART.
 */
internal class VmViewFactory(
    classpath: List<Path>,
    peerFactory: PeerFactory,
    /** Test seam: overrides where class bytes come from (default: the [classpath] class dirs + jars). */
    source: ClassBytesSource? = null,
    /** The app's package, whose `R` every library's `R` aliases (see [useResourceClasses]); null for none. */
    appPackage: String? = null,
) : LayoutInflater.Factory2, AutoCloseable {

    private val bootLoader: ClassLoader =
        View::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
    private val bootLoadable = ConcurrentHashMap<String, Boolean>()

    // Class dirs are consulted BEFORE jars so the user's freshly built classes shadow a same-named class in a
    // library jar; within the jars the caller's order decides. `R` classes come only from the resource-class
    // jar ([useResourceClasses]), whose ids match the linked table. One open JarFile per jar for the factory's
    // lifetime (a render reads many classes); [close] releases them.
    private val classDirs = classpath.filter { Files.isDirectory(it) }
    private val jarFiles = classpath.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
        .mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }

    private val log = Log.logger("ide.preview")

    init {
        log.info("VM view factory: ${jarFiles.size} library jars, ${classDirs.size} class dirs on the interpret classpath")
    }

    /** The classes the VM read from the project's class DIRECTORIES, forgotten by [projectClassesChanged]. */
    private val projectClasses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The project's compiled classes changed on disk (a compile finished): forget the ones the VM loaded from
     * them, so the next render reads the new bytes, and keep every library class decoded. Rebuilding the whole
     * VM for this re-read and re-decoded every AppCompat/Material class, a cold render for a one-line edit to a
     * custom view. Safe between renders: each render inflates a fresh tree, so nothing still runs old code.
     */
    fun projectClassesChanged() {
        val stale = HashSet(projectClasses)
        projectClasses.clear()
        vm.forgetClasses { it in stale }
    }

    /** `com/example/app/R`, the class every library `R` resolves to. */
    private val appR: String? = appPackage?.replace('.', '/')?.plus("/R")

    /** The open resource-class jar (the preview's `R`) and the key of the file it was opened from. Swapped by
     *  [useResourceClasses] without rebuilding the VM. */
    @Volatile private var rJar: JarFile? = null
    private var rJarKey: String? = null

    private val vm = Vm(
        source = source ?: ClassBytesSource { internalName ->
            if (isResourceClass(internalName)) return@ClassBytesSource rJar?.let { jar ->
                jar.getJarEntry("$internalName.class")?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
            }
            classDirs.firstNotNullOfOrNull { d ->
                d.resolve("$internalName.class").takeIf { Files.isRegularFile(it) }
                    ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
            }?.also { projectClasses += internalName } ?: jarFiles.firstNotNullOfOrNull { jar ->
                jar.getJarEntry("$internalName.class")
                    ?.let { e -> jar.getInputStream(e).use { it.readBytes() } }
            }
        },
        policy = InterpretPolicy { internalName ->
            !isBootLoadable(internalName.replace('/', '.')) &&
                !internalName.startsWith("kotlin/") && !internalName.startsWith("kotlinx/")
        },
        // A view can post an interpreted callback (a Runnable / offset listener) to the render Looper that runs
        // AFTER the snapshot is drawn, outside the render's error boundary. Guard those proxy invocations so a
        // failure in one degrades (skipped) instead of crashing the render process.
        bridge = ReflectiveBridge(proxyExceptionSink = { t ->
            log.warn("interpreted preview callback failed (skipped): ${t.message ?: t.javaClass.simpleName}")
        }),
        peerFactory = peerFactory,
        // A library's `R` holds the app's table under its own package name (aapt2 `--extra-packages`), so it
        // IS the app's `R`: one class, initialized once, instead of a renamed copy per library.
        classAlias = ClassAlias { name ->
            val app = appR ?: return@ClassAlias null
            if (!isResourceClass(name) || name.startsWith("$app\$") || name == app) null
            else app + name.substring(name.lastIndexOf('/') + 2)
        },
        // A view asking `isInEditMode()` is told it is in a design tool, the convention custom views use to
        // skip what cannot run in one (network, databases, analytics).
        constantMethods = mapOf("isInEditMode()Z" to true),
        // One custom view's onMeasure/onLayout/onDraw failing (or looping) must not fail the frame: the call
        // falls back to the framework's own implementation, and the view is marked for the overlay + problems.
        peerFailure = PeerFailurePolicy { peer, className, method, error ->
            if (peer is View) brokenViews[peer] = "$method: ${describe(error)}"
            problems += Problem(className, "$method failed: ${describe(error)}")
        },
        peerStepBudget = PEER_STEP_BUDGET,
    )

    /** One thing that went wrong this render: the class it happened in and what. */
    class Problem(val className: String, val message: String)

    /** This render's problems and the views whose code failed mid-frame, reset by [beginRender]. */
    private val problems = java.util.Collections.synchronizedList(ArrayList<Problem>())
    private val brokenViews = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, String>())

    /** Start a render: forget the last one's problems and broken views. */
    fun beginRender() {
        problems.clear()
        brokenViews.clear()
    }

    /** What went wrong in the current render, in order. */
    fun problems(): List<Problem> = synchronized(problems) { problems.toList() }

    /** The views whose interpreted code failed during the current render, each with what failed. */
    fun brokenViews(): Map<View, String> = synchronized(brokenViews) { brokenViews.toMap() }

    private fun describe(t: Throwable): String = when (t) {
        is VmStepBudgetException -> "ran too long (stopped after ${t.budget / 1_000_000}M instructions)"
        else -> (t.cause ?: t).let { c -> c.javaClass.simpleName + (c.message?.let { ": " + it.take(160) } ?: "") }
    }

    /**
     * Reads `R` classes from [jar] from now on. When it is a different file (or the same one rewritten) the VM
     * forgets the `R` classes it loaded, so the next render reads the new ids, and keeps everything else: an
     * edit that adds an `@+id` no longer costs a new VM and a cold re-inflate.
     */
    fun useResourceClasses(jar: Path?) {
        val key = jar?.let { "$it:${runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0)}:${runCatching { Files.size(it) }.getOrDefault(0)}" }
        if (key == rJarKey) return
        val old = rJar
        rJar = jar?.let { runCatching { JarFile(it.toFile()) }.getOrNull() }
        rJarKey = key
        old?.let { runCatching { it.close() } }
        vm.forgetClasses(::isResourceClass)
    }

    /** `…/R` or `…/R$type`: an Android resource class. */
    private fun isResourceClass(internalName: String): Boolean {
        val simple = internalName.substring(internalName.lastIndexOf('/') + 1)
        return simple == "R" || simple.startsWith("R\$")
    }

    /** Total interpreted bytecode instructions executed by this factory's VM (diagnostics / throughput). */
    val steps: Long get() = vm.steps

    private fun isBootLoadable(binaryName: String): Boolean = bootLoadable.getOrPut(binaryName) {
        runCatching { Class.forName(binaryName, false, bootLoader) }.getOrNull() != null
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
        createView(name, context, attrs)

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
        createView(name, context, attrs)

    /**
     * The interpreted view for tag [name] as its real peer, or null to defer to the framework's default
     * inflation (a framework short tag, or a fully-qualified name the boot classloader can load). A dotted tag
     * the VM claims but can't construct as a View throws an [InflateException] naming the reason — the render's
     * error path surfaces it instead of a bare ClassNotFoundException from the default path.
     */
    fun createView(name: String, context: Context, attrs: AttributeSet?): View? {
        if ('.' !in name) return null
        if (isBootLoadable(name)) return null
        if (!vm.hasInterpretedClass(name)) {
            // A dotted tag the boot loader can't provide and the VM has no bytes for: a project class not compiled
            // yet, or a library that is not resolved. A stand-in keeps the rest of the layout rendering.
            log.info("VM view factory: no interpreted bytes for <$name> — not on the preview classpath")
            return placeholder(name, context, attrs, MISSING_CLASS)
        }
        return try {
            vm.withStepBudget(CONSTRUCTOR_STEP_BUDGET) { construct(name, context, attrs) }
        } catch (t: Throwable) {
            if (t is dev.ide.jvm.VmInterruptedException) throw t
            log.warn("VM view factory: <$name> could not be created: ${describe(t)}")
            placeholder(name, context, attrs, "Could not be created: ${describe(t)}")
        }
    }

    /** A stand-in for [name] that renders in its place (see [PreviewPlaceholderView]), recorded as a problem. */
    private fun placeholder(name: String, context: Context, attrs: AttributeSet?, reason: String): View {
        problems += Problem(name, reason)
        return PreviewPlaceholderView(context, attrs, name, reason)
    }

    private fun construct(name: String, context: Context, attrs: AttributeSet?): View? {
        val ctors = vm.interpretedConstructors(name)
        fun ctor(vararg descs: String): VmMethodView? =
            ctors.firstOrNull { it.paramDescriptors == descs.toList() }
        // The framework inflater's two-argument contract first, then the common three-argument form with a
        // zero default style, then a bare (Context) constructor.
        val pick = ctor(CONTEXT, ATTRS)?.let { it to listOf(context, attrs) }
            ?: ctor(CONTEXT, ATTRS, "I")?.let { it to listOf(context, attrs, 0) }
            ?: ctor(CONTEXT)?.let { it to listOf<Any?>(context) }
            ?: throw InflateException("interpreted view $name has no (Context, AttributeSet) constructor")
        val instance = pick.first.invoke(null, pick.second)
        return instance as? View
            ?: throw InflateException(
                "$name resolved to an interpreted class that is not a View" +
                    (instance?.let { " (peer ${it.javaClass.superclass?.name})" } ?: "")
            )
    }

    /**
     * Fills [view], when it is the project's RecyclerView, with [count] items of [layoutId] through the generated
     * `ToolsList` installer (`dev.ide.android.support.PreviewToolsClasses`, on the interpret classpath). False when
     * [view] is not a RecyclerView or the installer is not on the classpath.
     */
    fun installListItems(view: View, layoutId: Int, count: Int): Boolean {
        if (!vm.isInterpretedInstanceOf(view, RECYCLER_VIEW) || !vm.hasInterpretedClass(TOOLS_LIST)) return false
        val install = vm.interpretedMethods(TOOLS_LIST).firstOrNull { it.name == "install" && it.isStatic } ?: return false
        install.invoke(null, listOf(view, layoutId, count))
        return true
    }

    override fun close() {
        jarFiles.forEach { runCatching { it.close() } }
        rJar?.let { runCatching { it.close() } }
    }

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val ATTRS = "Landroid/util/AttributeSet;"
        const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"
        const val TOOLS_LIST = "dev.ide.preview.tools.ToolsList"

        /** Instructions a view's constructor may run before it is taken to be stuck (Material views run a lot). */
        const val CONSTRUCTOR_STEP_BUDGET = 60_000_000L

        /** Instructions one onMeasure/onLayout/onDraw call may run. */
        const val PEER_STEP_BUDGET = 30_000_000L

        const val MISSING_CLASS = "Not compiled yet, or its library is not resolved"
    }
}
