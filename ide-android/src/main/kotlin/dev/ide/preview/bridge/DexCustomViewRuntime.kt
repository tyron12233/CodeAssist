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
import dev.ide.preview.impl.CustomViewRuntime
import dev.ide.preview.impl.StyledAttrResolver
import java.io.File
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
        if (inputs.isEmpty()) return null

        val dexOut = File(cacheDir, "preview-dex").apply { deleteRecursively(); mkdirs() }
        val result = runCatching { D8InProcessDexer().dex(inputs, androidJar, minApi, false, dexOut.toPath()) }.getOrNull()
        if (result == null || !result.success) return null

        val dexes = dexOut.walkTopDown().filter { it.extension == "dex" }.map { it.absolutePath }.toList()
        if (dexes.isEmpty()) return null
        val optimized = File(cacheDir, "preview-oat").apply { mkdirs() }
        val loader = DexClassLoader(dexes.joinToString(File.pathSeparator), optimized.absolutePath, null, javaClass.classLoader)

        return object : CustomViewFactory {
            override fun create(fqName: String, attrs: AttrReader, ctx: RenderContext): RenderNode? {
                val rawAttrs = HashMap<String, String>()
                for (i in 0 until attrs.count) rawAttrs[attrs.name(i)] = attrs.value(i)
                Bridges.styledResolver.set(styled)
                return try {
                    val cls = loader.loadClass(fqName)
                    val ctor = cls.getConstructor(Context::class.java, AttributeSet::class.java)
                    val view = ctor.newInstance(context, BridgeAttributeSet(rawAttrs)) as? BridgeView ?: return null
                    RenderNode(host = AndroidViewHost(view)).also { it.tag = fqName }
                } catch (t: Throwable) {
                    null
                } finally {
                    Bridges.styledResolver.remove()
                }
            }
        }
    }
}
