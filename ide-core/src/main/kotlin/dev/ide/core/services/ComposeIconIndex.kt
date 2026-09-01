package dev.ide.core.services

import dev.ide.model.ClasspathEntryKind
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Which `Icons.*` properties a module can actually reference, read from its classpath.
 *
 * The Compose icon libraries generate one file per icon, so `Icons.Filled.Home` is backed by a class named
 * `androidx.compose.material.icons.filled.HomeKt`. Listing those class names is therefore an exact answer to
 * "what can this module write", and it needs no class loading, no reflection and no compilation: just the
 * entry names in the jars already on the module's classpath.
 *
 * The bundled `material-icons-core` artifact carries only a handful of icons; the full set arrives with
 * `material-icons-extended`. Both are read the same way, so a module gets exactly the icons it has.
 */
internal object ComposeIconIndex {

    /** One icon available on the classpath: its property name plus the style packages that declare it. */
    data class Entry(val name: String, val styles: Set<String>)

    /** The icon-package prefixes to scan, mapped to the style name the picker shows. */
    private val STYLE_PACKAGES = mapOf(
        "androidx/compose/material/icons/filled/" to "filled",
        "androidx/compose/material/icons/outlined/" to "outlined",
        "androidx/compose/material/icons/rounded/" to "rounded",
        "androidx/compose/material/icons/sharp/" to "sharp",
        "androidx/compose/material/icons/twotone/" to "twotone",
    )

    /**
     * Icons on [module]'s compile classpath, sorted by name. Empty when no Compose icon library is present,
     * which is how the picker knows to offer adding the dependency instead.
     */
    fun scan(module: Module): List<Entry> {
        val found = HashMap<String, MutableSet<String>>()
        for (entry in module.classpath(DependencyScope.IMPLEMENTATION).entries) {
            if (entry.kind == ClasspathEntryKind.SDK_BOOTCLASSPATH) continue
            val root = runCatching { Paths.get(entry.root.path) }.getOrNull() ?: continue
            if (!root.exists()) continue
            if (root.isDirectory()) scanDirectory(root, found) else scanArchive(root, found)
        }
        return found.entries
            .map { (name, styles) -> Entry(name, styles.toSortedSet()) }
            .sortedBy { it.name }
    }

    /** True when [module] has any Compose icon library at all. */
    fun available(module: Module): Boolean = scan(module).isNotEmpty()

    private fun scanArchive(archive: Path, out: MutableMap<String, MutableSet<String>>) {
        if (archive.extension.lowercase() !in ARCHIVE_EXTENSIONS) return
        runCatching {
            ZipFile(archive.toFile()).use { zip ->
                val names = zip.entries()
                while (names.hasMoreElements()) {
                    accept(names.nextElement().name, out)
                }
            }
        }
    }

    private fun scanDirectory(dir: Path, out: MutableMap<String, MutableSet<String>>) {
        // A module output directory: only the icon packages matter, so walk straight to them.
        for ((prefix, _) in STYLE_PACKAGES) {
            val packageDir = dir.resolve(prefix)
            if (!packageDir.isDirectory()) continue
            runCatching {
                Files.list(packageDir).use { stream ->
                    stream.forEach { file -> accept(prefix + file.name, out) }
                }
            }
        }
    }

    /** Record [entryName] when it is an icon class in one of the style packages. */
    private fun accept(entryName: String, out: MutableMap<String, MutableSet<String>>) {
        if (!entryName.endsWith(ICON_CLASS_SUFFIX)) return
        val style = STYLE_PACKAGES.entries.firstOrNull { entryName.startsWith(it.key) } ?: return
        val simple = entryName.removePrefix(style.key).removeSuffix(ICON_CLASS_SUFFIX)
        // Generated icon files sit directly in the package, and a nested class is not an icon.
        if (simple.isEmpty() || simple.contains('/') || simple.contains('$')) return
        out.getOrPut(propertyName(simple)) { HashSet() } += style.value
    }

    /**
     * The property name for an icon class. The generator names the file after the property, so `HomeKt` is
     * `Home`; a leading underscore marks a name that would otherwise start with a digit (`_1kKt` is `1k`,
     * referenced in source as `Icons.Filled.\`1k\``).
     */
    private fun propertyName(className: String): String = className.removePrefix("_")

    /**
     * `ShoppingCart` reads as "Shopping cart" in the picker: sentence case, matching how the icon
     * repositories label the same icon, so the Compose tab and the library tab agree.
     *
     * A run of capitals is one word and is left alone (`SDCard` stays `SDCard`), because lowercasing an
     * acronym reads as a typo.
     */
    fun displayName(property: String): String {
        val words = ArrayList<String>()
        val current = StringBuilder()
        for ((index, ch) in property.withIndex()) {
            if (index > 0 && ch.isUpperCase() && !property[index - 1].isUpperCase()) {
                words += current.toString()
                current.clear()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) words += current.toString()
        if (words.isEmpty()) return property
        return words.mapIndexed { index, word ->
            when {
                index == 0 -> word.replaceFirstChar { it.uppercaseChar() }
                word.all { it.isUpperCase() || !it.isLetter() } -> word
                else -> word.replaceFirstChar { it.lowercaseChar() }
            }
        }.joinToString(" ")
    }

    /**
     * The Material Symbols name for an `Icons.*` property, which is the same icon under the naming the icon
     * repositories use: `ShoppingCart` is `shopping_cart`.
     */
    fun repositoryName(property: String): String {
        val out = StringBuilder(property.length + 4)
        for ((index, ch) in property.withIndex()) {
            if (index > 0 && ch.isUpperCase() && !property[index - 1].isUpperCase()) out.append('_')
            out.append(ch.lowercaseChar())
        }
        return out.toString()
    }

    /** The import a Compose icon reference needs, e.g. `androidx.compose.material.icons.filled.Home`. */
    fun importFor(property: String, style: String): String =
        "androidx.compose.material.icons.${style.lowercase()}.$property"

    private const val ICON_CLASS_SUFFIX = "Kt.class"
    private val ARCHIVE_EXTENSIONS = setOf("jar", "aar", "zip", "klib")
}
