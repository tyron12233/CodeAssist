package dev.ide.preview.bridge

import android.content.Context
import android.util.AttributeSet
import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.preview.AttrReader
import dev.ide.preview.RenderContext
import dev.ide.preview.RenderNode
import dev.ide.preview.bridge.widget.BridgeView
import dev.ide.preview.impl.CustomViewFactory
import dev.ide.preview.impl.CustomViewPreviewException
import dev.ide.preview.impl.CustomViewRuntime
import dev.ide.preview.impl.StyledAttrResolver
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * On-device [CustomViewRuntime]: D8-dexes the preview-compiled, BridgeRemapper-instrumented classes and
 * loads them through a [DexClassLoader] whose parent (the app classloader) exposes `android.*` and the
 * `dev.ide.preview.bridge.*` runtime. The returned factory instantiates each custom view against the app
 * [context] + a [BridgeAttributeSet], with the styled-attribute resolver bound on [Bridges] for the
 * duration of the constructor (so the instrumented `obtainStyledAttributes` resolves).
 */
class DexCustomViewRuntime(
    private val context: Context,
    private val androidJar: Path,
    private val cacheDir: File,
    private val minApi: Int,
) : CustomViewRuntime {

    override fun createFactory(classesDir: Path, deps: List<Path>, styled: StyledAttrResolver): CustomViewFactory? {
        val inputs = ArrayList<Path>()
        Files.walk(classesDir).use { w -> w.filter { it.toString().endsWith(".class") }.forEach { inputs.add(it) } }
        deps.filter { it.toString().endsWith(".jar") }.forEach { inputs.add(it) }
        if (inputs.isEmpty()) throw CustomViewPreviewException("nothing to dex for preview (no compiled classes)")

        val dexOut = File(cacheDir, "preview-dex").apply { deleteRecursively(); mkdirs() }
        // Cap at D8's max supported API: a newer device level (e.g. 37) only earns a "not supported … use 36 or
        // earlier" warning and adds noise to any real failure. The dex is debug-only and loads on any level.
        val dexApi = minOf(minApi, MAX_D8_API)
        val result = runCatching { D8InProcessDexer().dex(inputs, androidJar, dexApi, false, dexOut.toPath()) }
            .getOrElse { throw CustomViewPreviewException("D8 dexing of preview classes threw: ${it.message ?: it.javaClass.simpleName}", it) }
        if (!result.success) {
            throw CustomViewPreviewException("D8 dexing of preview classes failed: ${result.log.takeLast(3).joinToString(" / ").ifBlank { "(no diagnostics)" }}")
        }

        val dexFiles = dexOut.walkTopDown().filter { it.extension == "dex" }.toList()
        if (dexFiles.isEmpty()) throw CustomViewPreviewException("D8 produced no .dex output for preview classes")
        // ART refuses to load a dex that is still writable ("Writable dex file '…' is not allowed", W^X);
        // D8 emits owner-writable files, so clear every write bit before handing them to the class loader.
        dexFiles.forEach { it.setWritable(false, false) }
        val dexes = dexFiles.map { it.absolutePath }
        val optimized = File(cacheDir, "preview-oat").apply { mkdirs() }
        val loader = DexClassLoader(dexes.joinToString(File.pathSeparator), optimized.absolutePath, null, javaClass.classLoader)

        return object : CustomViewFactory {
            override fun create(fqName: String, attrs: AttrReader, ctx: RenderContext): RenderNode? {
                val rawAttrs = HashMap<String, String>()
                for (i in 0 until attrs.count) rawAttrs[attrs.name(i)] = attrs.value(i)
                Bridges.styledResolver.set(styled)
                return try {
                    val cls = try {
                        loader.loadClass(fqName)
                    } catch (e: Throwable) {
                        throw CustomViewPreviewException("class $fqName not found in preview dex (${e.javaClass.simpleName})", e)
                    }
                    val ctor = try {
                        cls.getConstructor(Context::class.java, AttributeSet::class.java)
                    } catch (e: NoSuchMethodException) {
                        throw CustomViewPreviewException("$fqName has no (Context, AttributeSet) constructor", e)
                    }
                    val instance = try {
                        ctor.newInstance(context, BridgeAttributeSet(rawAttrs))
                    } catch (e: InvocationTargetException) {
                        val cause = e.targetException ?: e
                        throw CustomViewPreviewException("$fqName constructor threw ${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}", cause)
                    }
                    val view = instance as? BridgeView
                        ?: throw CustomViewPreviewException("$fqName is not a bridged View subclass (got ${instance.javaClass.name}) — its base may not have been instrumented")
                    RenderNode(host = AndroidViewHost(view)).also { it.tag = fqName }
                } finally {
                    Bridges.styledResolver.remove()
                }
            }
        }
    }

    private companion object {
        /** Highest API level the bundled D8 accepts; a newer device level only earns a warning, so cap to it. */
        const val MAX_D8_API = 36
    }
}
