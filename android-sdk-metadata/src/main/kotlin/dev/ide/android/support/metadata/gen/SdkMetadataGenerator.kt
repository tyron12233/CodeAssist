package dev.ide.android.support.metadata.gen

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttrsXmlParser
import dev.ide.android.support.metadata.SdkMetadataCodec
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Generates the SDK metadata asset from a platform's `attrs.xml` + `android.jar`. The attribute formats /
 * enums / flags and `<declare-styleable>` groups come from `attrs.xml`; the View class hierarchy (so
 * `<Button>` inherits TextView/View attributes) and the widget tag list come from `android.jar`, read with
 * ASM's [ClassReader] (no class loading — `android.jar` stubs can't be loaded).
 *
 * Usage:
 * ```
 * ./gradlew :android-sdk-metadata:run --args \
 *   "$SDK/platforms/android-34/data/res/values/attrs.xml $SDK/platforms/android-34/android.jar \
 *    app/.platform/android-sdk-metadata.txt 34"
 * ```
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: <attrs.xml> <android.jar> <output.txt> [apiLevel]")
        return
    }
    val attrsPath = Path.of(args[0])
    val jarPath = Path.of(args[1])
    val outPath = Path.of(args[2])
    val api = args.getOrNull(3)?.toIntOrNull() ?: 0

    val parsed = AttrsXmlParser.parse(Files.readString(attrsPath))
    val hierarchy = readHierarchy(jarPath)

    val text = SdkMetadataCodec.write(api, parsed.attrs, parsed.styleables, hierarchy.superSimple, hierarchy.widgets)
    outPath.parent?.let { Files.createDirectories(it) }
    Files.writeString(outPath, text)
    println("Wrote ${outPath.toAbsolutePath()} — api=$api, ${parsed.attrs.size} attrs, ${parsed.styleables.size} styleables, ${hierarchy.widgets.size} widgets")
}

private class Hierarchy(
    val superSimple: Map<String, String>,
    val widgets: List<AndroidSdkMetadata.WidgetInfo>,
)

private const val VIEW = "android/view/View"
private const val VIEWGROUP = "android/view/ViewGroup"

/** Read every class from [jar], keep the View-subtree, and produce the simple-name super map + widget list. */
private fun readHierarchy(jar: Path): Hierarchy {
    val superInternal = HashMap<String, String?>()      // internal name → super internal name
    val access = HashMap<String, Int>()
    ZipFile(jar.toFile()).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory || !e.name.endsWith(".class")) continue
            val cr = runCatching { zip.getInputStream(e).use { ClassReader(it) } }.getOrNull() ?: continue
            superInternal[cr.className] = cr.superName
            access[cr.className] = cr.access
        }
    }

    fun isSubclassOf(name: String, target: String): Boolean {
        var cur: String? = name
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            if (cur == target) return true
            cur = superInternal[cur]
        }
        return false
    }

    fun simple(internal: String) = internal.substringAfterLast('/').substringAfterLast('$')

    val superSimple = LinkedHashMap<String, String>()
    val widgets = LinkedHashMap<String, AndroidSdkMetadata.WidgetInfo>() // dedup by simple name
    for ((internal, sup) in superInternal) {
        if (!isSubclassOf(internal, VIEW)) continue
        if (sup != null) superSimple[simple(internal)] = simple(sup)
        val acc = access[internal] ?: 0
        val usable = acc and Opcodes.ACC_PUBLIC != 0 &&
            acc and Opcodes.ACC_ABSTRACT == 0 &&
            acc and Opcodes.ACC_INTERFACE == 0
        if (usable) {
            val s = simple(internal)
            widgets.putIfAbsent(s, AndroidSdkMetadata.WidgetInfo(s, isSubclassOf(internal, VIEWGROUP)))
        }
    }
    return Hierarchy(superSimple, widgets.values.sortedBy { it.simpleName }.toList())
}
