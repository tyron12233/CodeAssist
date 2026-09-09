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
    /**
     * A scan result: the usable [widgets] plus a simple-name → super-simple-name map of every discovered View
     * subclass. The map lets attribute completion walk a custom/library view's `app:` attributes up its
     * ancestry (e.g. a `MaterialCardView` inheriting `CardView`'s `cardCornerRadius`), and resolve the
     * AppCompat substitutions' own styleables.
     */
    data class Scan(val widgets: List<Widget>, val superNames: Map<String, String>)

    fun scan(jars: List<Path>, frameworkWidgets: Map<String, Boolean> = emptyMap()): List<Widget> =
        scanAll(jars, frameworkWidgets).widgets

    fun scanAll(jars: List<Path>, frameworkWidgets: Map<String, Boolean> = emptyMap()): Scan {
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
        val superNames = LinkedHashMap<String, String>() // simple name → super simple name (View subclasses)
        for ((internal, _) in superInternal) {
            if ('$' in internal) continue                       // skip inner/anonymous classes
            if (isFrameworkOrRuntime(internal)) continue
            val classification = classify(internal)
            // Record ancestry for ANY View subclass — including abstract/non-instantiable bases — so attribute
            // inheritance resolves through them (e.g. MaterialButton → AppCompatButton); the super's simple
            // name terminates the chain at the framework base, which carries no `app:` styleable.
            if (classification?.first == true) {
                superInternal[internal]?.let { superNames.putIfAbsent(simpleOf(internal), simpleOf(it)) }
            }
            val acc = access[internal] ?: 0
            val usable = acc and Opcodes.ACC_PUBLIC != 0 &&
                acc and Opcodes.ACC_ABSTRACT == 0 &&
                acc and Opcodes.ACC_INTERFACE == 0
            if (!usable) continue
            val (isView, isViewGroup) = classification ?: continue
            if (!isView) continue
            val fqn = internal.replace('/', '.')
            out.putIfAbsent(fqn, Widget(fqn, isViewGroup))
        }
        return Scan(out.values.sortedBy { it.tag }, superNames)
    }

    /**
     * [scan] gated by a content fingerprint persisted at [cacheFile]: scanning a large dependency set is
     * expensive, but the result only changes when a jar does, so a session reuses the previous scan unless
     * the jar set (path + size + mtime) changed. The cache is best-effort — any I/O failure falls back to a
     * live scan.
     */
    fun cached(jars: List<Path>, cacheFile: Path, frameworkWidgets: Map<String, Boolean> = emptyMap()): List<Widget> =
        cachedScan(jars, cacheFile, frameworkWidgets).widgets

    /** [scanAll] gated by the same content fingerprint as [cached]; the cache now also persists the ancestry map. */
    fun cachedScan(jars: List<Path>, cacheFile: Path, frameworkWidgets: Map<String, Boolean> = emptyMap()): Scan {
        val fingerprint = fingerprintOf(jars)
        runCatching {
            if (Files.isRegularFile(cacheFile)) {
                val lines = String(Files.readAllBytes(cacheFile)).split('\n')
                if (lines.firstOrNull() == fingerprint) return parseScan(lines.drop(1))
            }
        }
        val result = scanAll(jars, frameworkWidgets)
        runCatching {
            cacheFile.parent?.let { Files.createDirectories(it) }
            Files.write(cacheFile, serialize(fingerprint, result).toByteArray())
        }
        return result
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
        // "v2" tags the cache layout (widgets + ancestry section); an older widgets-only cache won't match,
        // so it's transparently rescanned and rewritten in the new format.
        "v2|" + jars.sortedBy { it.toString() }.joinToString(";") { p ->
            val size = runCatching { Files.size(p) }.getOrDefault(0L)
            val mtime = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
            "$p:$size:$mtime"
        }

    /** Line separating the widget section from the ancestry section in the cache file. */
    private const val HIER_MARKER = " H"

    private fun serialize(fingerprint: String, scan: Scan): String =
        buildString {
            append(fingerprint).append('\n')
            scan.widgets.forEach { append(it.tag).append('\t').append(if (it.isViewGroup) 'G' else 'V').append('\n') }
            append(HIER_MARKER).append('\n')
            scan.superNames.forEach { (k, v) -> append(k).append('\t').append(v).append('\n') }
        }

    private fun parseScan(lines: List<String>): Scan {
        val widgets = ArrayList<Widget>()
        val supers = LinkedHashMap<String, String>()
        var inHierarchy = false
        for (line in lines) {
            if (line == HIER_MARKER) { inHierarchy = true; continue }
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val a = line.substring(0, tab)
            val b = line.substring(tab + 1).trim()
            if (inHierarchy) supers[a] = b else widgets += Widget(a, b == "G")
        }
        return Scan(widgets, supers)
    }
}
