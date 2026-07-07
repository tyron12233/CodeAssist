package dev.ide.core.gradle

/**
 * A tolerant reader for a Gradle version catalog (`gradle/libs.versions.toml`). Resolves the type-safe
 * accessors a modern build script uses — `libs.androidx.core.ktx`, `libs.bundles.compose`,
 * `libs.plugins.android.application` — back to Maven coordinates / plugin ids, so the importer can pick
 * up dependencies declared through a catalog instead of inline strings.
 *
 * NOT a full TOML parser (the shared [dev.ide.model.impl.format.Toml] one rejects the `version.ref` dotted
 * key that catalogs lean on): a line-oriented reader for exactly the four catalog sections, resolving each
 * entry independently so a single malformed line can't sink the rest. Accessor lookup normalizes `-`/`_`
 * to `.` (Gradle's own accessor derivation) so an alias `core-ktx` matches the accessor `libs.core.ktx`.
 */
internal class GradleVersionCatalog private constructor(
    val versions: Map<String, String>,
    val libraries: Map<String, CatalogEntry>,
    val plugins: Map<String, CatalogPlugin>,
    val bundles: Map<String, List<String>>,
) {
    val isEmpty: Boolean get() = libraries.isEmpty() && plugins.isEmpty() && bundles.isEmpty()

    /** The library for accessor [path] (already stripped of the `libs.` prefix), or null. */
    fun library(path: String): CatalogEntry? = libraries[normalize(path)]

    /** The plugin for accessor [path] (stripped of `libs.plugins.`), or null. */
    fun plugin(path: String): CatalogPlugin? = plugins[normalize(path)]

    /** The libraries in bundle accessor [path] (stripped of `libs.bundles.`). */
    fun bundle(path: String): List<CatalogEntry> = (bundles[normalize(path)] ?: emptyList()).mapNotNull { libraries[it] }

    companion object {
        val EMPTY = GradleVersionCatalog(emptyMap(), emptyMap(), emptyMap(), emptyMap())

        fun normalize(alias: String): String = alias.replace('-', '.').replace('_', '.')

        fun parse(text: String): GradleVersionCatalog {
            // Two passes: gather [versions] first so a later library can reference a version defined anywhere.
            val versions = LinkedHashMap<String, String>()
            forEachEntry(text) { section, key, value ->
                if (section == "versions") scalarVersion(value)?.let { versions[normalize(key)] = it }
            }
            val libraries = LinkedHashMap<String, CatalogEntry>()
            val plugins = LinkedHashMap<String, CatalogPlugin>()
            val bundles = LinkedHashMap<String, List<String>>()
            forEachEntry(text) { section, key, value ->
                val norm = normalize(key)
                when (section) {
                    "libraries" -> parseLibrary(value, versions)?.let { libraries[norm] = it }
                    "plugins" -> parsePlugin(value, versions)?.let { plugins[norm] = it }
                    "bundles" -> stringList(value).map { normalize(it) }.takeIf { it.isNotEmpty() }?.let { bundles[norm] = it }
                }
            }
            return GradleVersionCatalog(versions, libraries, plugins, bundles)
        }

        private inline fun forEachEntry(text: String, body: (section: String, key: String, value: String) -> Unit) {
            var section = ""
            for (raw in text.lineSequence()) {
                val line = stripHashComment(raw).trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[") && line.endsWith("]")) { section = line.trim('[', ']').trim().lowercase(); continue }
                val eq = topLevelEquals(line)
                if (eq < 0) continue
                val key = line.substring(0, eq).trim().trim('"', '\'')
                val value = line.substring(eq + 1).trim()
                runCatching { body(section, key, value) }
            }
        }

        private fun parseLibrary(value: String, versions: Map<String, String>): CatalogEntry? {
            if (value.startsWith("\"") || value.startsWith("'")) return coordinate(value.trim('"', '\''))
            if (value.startsWith("{")) {
                val t = inlineTable(value)
                var group: String? = null
                var name: String? = null
                t["module"]?.split(":")?.let { if (it.size >= 2) { group = it[0]; name = it[1] } }
                t["group"]?.let { group = it }
                t["name"]?.let { name = it }
                val version = t["version"]?.takeIf { !it.startsWith("{") }
                    ?: t["version.ref"]?.let { versions[normalize(it)] } ?: ""
                if (group != null && name != null) return CatalogEntry(group!!, name!!, version)
            }
            return null
        }

        private fun parsePlugin(value: String, versions: Map<String, String>): CatalogPlugin? {
            if (value.startsWith("\"") || value.startsWith("'")) {
                val s = value.trim('"', '\'')
                val idx = s.lastIndexOf(':')
                return if (idx > 0) CatalogPlugin(s.substring(0, idx), s.substring(idx + 1)) else CatalogPlugin(s, "")
            }
            if (value.startsWith("{")) {
                val t = inlineTable(value)
                val id = t["id"] ?: return null
                val version = t["version"]?.takeIf { !it.startsWith("{") }
                    ?: t["version.ref"]?.let { versions[normalize(it)] } ?: ""
                return CatalogPlugin(id, version)
            }
            return null
        }

        /** A `[versions]` value: a quoted string, or a rich-version inline table's `require`/`strictly`/`prefer`. */
        private fun scalarVersion(value: String): String? = when {
            value.startsWith("\"") || value.startsWith("'") -> value.trim('"', '\'')
            value.startsWith("{") -> inlineTable(value).let { it["require"] ?: it["strictly"] ?: it["prefer"] }
            else -> null
        }

        private fun coordinate(coord: String): CatalogEntry? {
            val p = coord.split(":")
            return when (p.size) {
                2 -> CatalogEntry(p[0], p[1], "")
                3 -> CatalogEntry(p[0], p[1], p[2])
                else -> null
            }
        }

        /** Parse `{ k = v, k2 = "v2" }` — keys may be dotted (`version.ref`), values are string scalars. */
        private fun inlineTable(s: String): Map<String, String> {
            val inner = s.trim().removePrefix("{").removeSuffix("}")
            val out = LinkedHashMap<String, String>()
            for (part in splitTopLevel(inner)) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val k = part.substring(0, eq).trim()
                val v = part.substring(eq + 1).trim().trim('"', '\'')
                if (k.isNotEmpty()) out[k] = v
            }
            return out
        }

        private fun stringList(value: String): List<String> =
            Regex("""['"]([^'"]+)['"]""").findAll(value).map { it.groupValues[1] }.toList()

        private fun splitTopLevel(s: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var depth = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' || c == '\'' -> { val e = quoteEnd(s, i); cur.append(s, i, e); i = e; continue }
                    c == '{' || c == '[' -> { depth++; cur.append(c) }
                    c == '}' || c == ']' -> { if (depth > 0) depth--; cur.append(c) }
                    c == ',' && depth == 0 -> { out.add(cur.toString()); cur.setLength(0) }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotBlank()) out.add(cur.toString())
            return out
        }

        private fun topLevelEquals(line: String): Int {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"' || c == '\'') { i = quoteEnd(line, i); continue }
                if (c == '=') return i
                i++
            }
            return -1
        }

        private fun quoteEnd(s: String, start: Int): Int {
            val q = s[start]
            var i = start + 1
            while (i < s.length) { if (s[i] == '\\') i += 2 else if (s[i] == q) return i + 1 else i++ }
            return s.length
        }

        private fun stripHashComment(line: String): String {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '"' || c == '\'') { i = quoteEnd(line, i); continue }
                if (c == '#') return line.substring(0, i)
                i++
            }
            return line
        }
    }
}

/** A resolved catalog library. [version] is blank when the catalog gives no version (a BOM fills it in). */
internal data class CatalogEntry(val group: String, val name: String, val version: String) {
    val coordinate: String get() = if (version.isBlank()) "$group:$name" else "$group:$name:$version"
}

internal data class CatalogPlugin(val id: String, val version: String)
