package dev.ide.android.support.metadata

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Discovers custom `android.view.View` subclasses on a module's compile classpath (library jars, including
 * AAR `classes.jar`) so the XML layout editor can suggest them as element tags — e.g.
 * `com.google.android.material.button.MaterialButton`, `androidx.appcompat.widget.AppCompatTextView`. The
 * framework widgets (`TextView`, `LinearLayout`, …) are already offered by the bundled SDK metadata under
 * their simple names; this fills the gap for everything else.
 *
 * Reads bytecode with ASM's [ClassReader] (no class loading — these jars target Android and can't be loaded
 * on the host), building one super-name map across all jars so a subclass is recognised even when its View
 * ancestor lives in a different jar (e.g. a Material view extending an AppCompat view extending the
 * framework `View`). Framework / JDK / Kotlin-runtime packages are excluded: framework views are covered by
 * the SDK metadata, and a layout must spell a non-framework view with its fully-qualified name anyway, which
 * is exactly the [Widget.tag] emitted here.
 */
object CustomViewScanner {

    private const val VIEW = "android/view/View"
    private const val VIEWGROUP = "android/view/ViewGroup"

    /**
     * Scan [jars] for usable (public, concrete, non-inner) View subclasses, returning one [Widget] per class
     * with its fully-qualified dotted name as the tag.
     *
     * Library views ultimately extend a *framework* base class (`android.widget.TextView`, `android.view.View`,
     * …) that does not live in any of [jars], so the chain can't be walked all the way to `android/view/View`
     * by bytecode alone. [frameworkWidgets] (simple class name → is-it-a-`ViewGroup`, from the bundled SDK
     * metadata) seeds that knowledge: a class is a View as soon as an ancestor is a framework widget, and it's
     * a `ViewGroup` if that ancestor is one (e.g. `MaterialButton → AppCompatButton → android.widget.Button`).
     */
    fun scan(jars: List<Path>, frameworkWidgets: Map<String, Boolean> = emptyMap()): List<Widget> {
        val superInternal = HashMap<String, String?>() // internal name → super internal name
        val access = HashMap<String, Int>()
        for (jar in jars) {
            if (!Files.isRegularFile(jar)) continue
            runCatching {
                ZipFile(jar.toFile()).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (e.isDirectory || !e.name.endsWith(".class")) continue
                        val cr = runCatching { zip.getInputStream(e).use { ClassReader(it) } }.getOrNull() ?: continue
                        // First jar wins for a duplicated class (matches classpath shadowing order).
                        superInternal.putIfAbsent(cr.className, cr.superName)
                        access.putIfAbsent(cr.className, cr.access)
                    }
                }
            }
        }

        fun simpleOf(internal: String): String = internal.substringAfterLast('/').substringAfterLast('$')

        // Walk the ancestry (starting at the *super* — a class's own name coinciding with a framework widget
        // must not classify it). Returns whether the class is a View and, if so, whether it's a ViewGroup.
        fun classify(internal: String): Pair<Boolean, Boolean>? {
            var cur: String? = superInternal[internal]
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                if (cur == VIEWGROUP) return true to true
                if (cur == VIEW) return true to false
                val fw = frameworkWidgets[simpleOf(cur)]
                if (fw != null) return true to fw
                cur = superInternal[cur]
            }
            return null
        }

        val out = LinkedHashMap<String, Widget>() // dedup by fqn
        for ((internal, _) in superInternal) {
            if ('$' in internal) continue                       // skip inner/anonymous classes
            if (isFrameworkOrRuntime(internal)) continue
            val acc = access[internal] ?: 0
            val usable = acc and Opcodes.ACC_PUBLIC != 0 &&
                acc and Opcodes.ACC_ABSTRACT == 0 &&
                acc and Opcodes.ACC_INTERFACE == 0
            if (!usable) continue
            val (isView, isViewGroup) = classify(internal) ?: continue
            if (!isView) continue
            val fqn = internal.replace('/', '.')
            out.putIfAbsent(fqn, Widget(fqn, isViewGroup))
        }
        return out.values.sortedBy { it.tag }
    }

    /**
     * [scan] gated by a content fingerprint persisted at [cacheFile]: scanning a large dependency set is
     * expensive, but the result only changes when a jar does, so a session reuses the previous scan unless
     * the jar set (path + size + mtime) changed. The cache is best-effort — any I/O failure falls back to a
     * live scan.
     */
    fun cached(jars: List<Path>, cacheFile: Path, frameworkWidgets: Map<String, Boolean> = emptyMap()): List<Widget> {
        val fingerprint = fingerprintOf(jars)
        runCatching {
            if (Files.isRegularFile(cacheFile)) {
                val lines = String(Files.readAllBytes(cacheFile)).split('\n')
                if (lines.firstOrNull() == fingerprint) return parse(lines.drop(1))
            }
        }
        val widgets = scan(jars, frameworkWidgets)
        runCatching {
            cacheFile.parent?.let { Files.createDirectories(it) }
            Files.write(cacheFile, serialize(fingerprint, widgets).toByteArray())
        }
        return widgets
    }

    /** Framework, JDK, Kotlin and tooling packages that should never appear as a custom layout tag. */
    private fun isFrameworkOrRuntime(internal: String): Boolean =
        internal.startsWith("android/") ||   // framework views (covered by SDK metadata); NB: androidx/ is kept
            internal.startsWith("java/") ||
            internal.startsWith("javax/") ||
            internal.startsWith("kotlin/") ||
            internal.startsWith("dalvik/") ||
            internal.startsWith("org/intellij/") ||
            internal.startsWith("org/jetbrains/annotations/")

    private fun fingerprintOf(jars: List<Path>): String =
        jars.sortedBy { it.toString() }.joinToString(";") { p ->
            val size = runCatching { Files.size(p) }.getOrDefault(0L)
            val mtime = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
            "$p:$size:$mtime"
        }

    private fun serialize(fingerprint: String, widgets: List<Widget>): String =
        buildString {
            append(fingerprint).append('\n')
            widgets.forEach { append(it.tag).append('\t').append(if (it.isViewGroup) 'G' else 'V').append('\n') }
        }

    private fun parse(lines: List<String>): List<Widget> =
        lines.filter { it.isNotBlank() }.mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@mapNotNull null
            Widget(line.substring(0, tab), line.substring(tab + 1).trim() == "G")
        }
}
