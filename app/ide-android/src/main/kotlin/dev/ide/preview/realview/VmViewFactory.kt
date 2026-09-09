package dev.ide.preview.realview

import android.content.Context
import android.util.AttributeSet
import android.view.InflateException
import android.view.LayoutInflater
import android.view.View
import dev.ide.jvm.ClassBytesSource
import dev.ide.jvm.InterpretPolicy
import dev.ide.jvm.PeerFactory
import dev.ide.jvm.ReflectiveBridge
import dev.ide.jvm.Vm
import dev.ide.jvm.VmMethodView
import dev.ide.jvm.hasInterpretedClass
import dev.ide.jvm.interpretedConstructors
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
) : LayoutInflater.Factory2, AutoCloseable {

    private val bootLoader: ClassLoader =
        View::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
    private val bootLoadable = ConcurrentHashMap<String, Boolean>()

    // Class dirs are consulted BEFORE jars so the user's freshly built classes shadow a same-named class in a
    // library jar; within the jars the caller's order decides (the preview puts `preview-r.jar` first so its
    // R — ids matching the relinked arsc — shadows any stale R elsewhere). One open JarFile per jar for the
    // factory's lifetime (a render reads many classes); [close] releases them.
    private val classDirs = classpath.filter { Files.isDirectory(it) }
    private val jarFiles = classpath.filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
        .mapNotNull { runCatching { JarFile(it.toFile()) }.getOrNull() }

    private val log = Log.logger("ide.preview")

    init {
        log.info("VM view factory: ${jarFiles.size} library jars, ${classDirs.size} class dirs on the interpret classpath")
    }

    private val vm = Vm(
        source = source ?: ClassBytesSource { internalName ->
            classDirs.firstNotNullOfOrNull { d ->
                d.resolve("$internalName.class").takeIf { Files.isRegularFile(it) }
                    ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
            } ?: jarFiles.firstNotNullOfOrNull { jar ->
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
    )

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
        if (!vm.hasInterpretedClass(name)) {
            // A dotted tag the boot loader can't provide and the VM has no bytes for: the library jar isn't on
            // the classpath (unresolved dependency, or a class dir not yet built). Defer to the default inflater
            // (which will report the ClassNotFoundException) but note why here.
            log.info("VM view factory: no interpreted bytes for <$name> — not on the preview classpath")
            return null
        }
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

    override fun close() {
        jarFiles.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val ATTRS = "Landroid/util/AttributeSet;"
    }
}
