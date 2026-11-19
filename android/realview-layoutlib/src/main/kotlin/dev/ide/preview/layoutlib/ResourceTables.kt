package dev.ide.preview.layoutlib

import com.android.ide.common.rendering.api.ArrayResourceValue
import com.android.ide.common.rendering.api.ArrayResourceValueImpl
import com.android.ide.common.rendering.api.AttrResourceValue
import com.android.ide.common.rendering.api.PluralsResourceValue
import com.android.ide.common.rendering.api.PluralsResourceValueImpl
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceValue
import com.android.ide.common.rendering.api.ResourceValueImpl
import com.android.ide.common.rendering.api.StyleItemResourceValue
import com.android.ide.common.rendering.api.StyleResourceValue
import com.android.ide.common.rendering.api.StyleableResourceValue
import com.android.ide.common.resources.ResourceMergerItem
import com.android.ide.common.resources.ResourceSet
import com.android.ide.common.resources.ResourceValueMap
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.android.utils.NullLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.EnumMap

/** The resources of one namespace, already chosen for one configuration: what layoutlib's resolver is built from. */
internal typealias ResourceTable = MutableMap<ResourceType, ResourceValueMap>

/**
 * Resource folders read into a [ResourceTable], and kept as one file between runs.
 *
 * Reading the folders is XML parsing. On a JVM that only interprets (the one embedded on iOS) the framework's
 * take 11 seconds and a Material app's libraries 5, every launch, for values that change only when the folders
 * do. The file holds the values already chosen for one configuration as serialized objects, and is used while
 * a fingerprint of the folders (every file's name, size and time) and of the configuration still matches.
 */
internal object ResourceTables {
    private const val FORMAT = 1

    fun load(cache: File, namespace: ResourceNamespace, dirs: List<File>, config: FolderConfiguration, configText: String): ResourceTable {
        val print = fingerprint(dirs, configText)
        read(cache, print)?.let { return it }
        val table: ResourceTable = EnumMap(ResourceType::class.java)
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            // Later folders win, so the project's own values replace a library's of the same name.
            for ((type, values) in parse(namespace, dir, config)) {
                val into = table.getOrPut(type) { ResourceValueMap.create() }
                for (value in values.values()) into.put(plain(value))
            }
        }
        write(cache, print, table)
        return table
    }

    /** The best match per (type, name) in [dir] for [config]. */
    private fun parse(namespace: ResourceNamespace, dir: File, config: FolderConfiguration): ResourceTable {
        val set = ResourceSet(dir.path, namespace, null, false, null)
        set.setShouldParseResourceIds(true)
        set.setTrackSourcePositions(false)
        // The framework declares some values once per product (phone, tablet, tv); the first read is kept.
        set.setCheckDuplicates(false)
        set.addSource(dir)
        set.loadFromFiles(NullLogger())
        val byName = HashMap<String, MutableList<ResourceMergerItem>>()
        for (item in set.dataMap.values()) byName.getOrPut("${item.type}/${item.name}") { ArrayList() }.add(item)
        val table: ResourceTable = EnumMap(ResourceType::class.java)
        for (candidates in byName.values) {
            val best = (if (candidates.size == 1) candidates[0] else config.findMatchingConfigurable(candidates)) ?: continue
            val value = best.resourceValue ?: continue
            table.getOrPut(best.type) { ResourceValueMap.create() }.put(value)
        }
        return table
    }

    private fun read(cache: File, print: Long): ResourceTable? {
        if (!cache.isFile) return null
        return runCatching {
            ObjectInputStream(BufferedInputStream(cache.inputStream(), 1 shl 16)).use { input ->
                if (input.readInt() != FORMAT || input.readLong() != print) return null
                val table: ResourceTable = EnumMap(ResourceType::class.java)
                repeat(input.readInt()) {
                    val value = input.readObject() as ResourceValue
                    restorePrefixes(value)
                    table.getOrPut(value.resourceType) { ResourceValueMap.create() }.put(value)
                }
                table
            }
        }.getOrNull()
    }

    private fun write(cache: File, print: Long, table: ResourceTable) {
        runCatching {
            cache.parentFile?.mkdirs()
            val tmp = File(cache.path + ".tmp")
            ObjectOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                out.writeInt(FORMAT)
                out.writeLong(print)
                out.writeInt(table.values.sumOf { it.size() })
                for (values in table.values) for (value in values.values()) out.writeObject(value)
            }
            if (!tmp.renameTo(cache)) { cache.delete(); tmp.renameTo(cache) }
        }
    }

    /** Names, sizes and times of every file under [dirs]: cheap to take, and different when anything in them is. */
    private fun fingerprint(dirs: List<File>, config: String): Long {
        var h = 1125899906842597L * 31 + config.hashCode()
        for (dir in dirs) {
            h = 31 * h + dir.path.hashCode()
            for (file in dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                h = 31 * (31 * (31 * h + file.name.hashCode()) + file.length()) + file.lastModified()
            }
        }
        return h
    }

    /** The loader's arrays and plurals are anonymous classes tied to its own items; plain ones are what is stored. */
    private fun plain(value: ResourceValue): ResourceValue {
        if (value.javaClass.name.startsWith("com.android.ide.common.rendering.api.")) return value
        return when (value) {
            is ArrayResourceValue -> ArrayResourceValueImpl(value.asReference(), value.libraryName).also { copy ->
                for (i in 0 until value.elementCount) copy.addElement(value.getElement(i))
            }
            is PluralsResourceValue -> PluralsResourceValueImpl(value.asReference(), value.value, value.libraryName).also { copy ->
                for (i in 0 until value.pluralsCount) copy.addPlural(value.getQuantity(i), value.getValue(i))
            }
            else -> value
        }
    }

    /** The prefixes resource files use for the namespaces a value can name. */
    private val PREFIXES = object : ResourceNamespace.Resolver {
        override fun prefixToUri(prefix: String): String? = when (prefix) {
            "android" -> ResourceNamespace.ANDROID.xmlNamespaceUri
            "app" -> ResourceNamespace.RES_AUTO.xmlNamespaceUri
            "tools" -> ResourceNamespace.TOOLS.xmlNamespaceUri
            else -> null
        }

        override fun uriToPrefix(uri: String): String? = when (uri) {
            ResourceNamespace.ANDROID.xmlNamespaceUri -> "android"
            ResourceNamespace.RES_AUTO.xmlNamespaceUri -> "app"
            else -> null
        }
    }

    /** A value's prefix resolver is not stored with it; without one, `android:textColor` in a style cannot be read. */
    private fun restorePrefixes(value: ResourceValue) {
        (value as? ResourceValueImpl)?.setNamespaceResolver(PREFIXES)
        if (value is StyleResourceValue) {
            for (item: StyleItemResourceValue in value.definedItems) (item as? ResourceValueImpl)?.setNamespaceResolver(PREFIXES)
        }
        if (value is StyleableResourceValue) {
            for (attr: AttrResourceValue in value.allAttributes) (attr as? ResourceValueImpl)?.setNamespaceResolver(PREFIXES)
        }
    }
}
