package dev.ide.build.jvm.compose

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.stream.Collectors
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Reproduces what the Compose Multiplatform Gradle plugin's resource generator produces, so a module whose
 * sources say `import dev.ide.ui.generated.resources.Res` compiles under CodeAssist's own build.
 *
 * Two outputs, both required and neither useful alone:
 *
 *  * **Kotlin** ([kotlinDir]): the `Res` object plus one typed accessor per resource
 *    (`Res.string.projects`, `Res.drawable.preview_2048`, `Res.font.jetbrains_mono_bold`), each a
 *    `StringResource`/`DrawableResource`/`FontResource` naming the qualified files that can satisfy it.
 *  * **Staged resources** ([resourcesDir]): the files those accessors name, under
 *    `composeResources/<package>/`, which is where `components-resources` looks them up at runtime. Files
 *    are copied through; the XML in a `values` directory is *converted*, because strings do not ship as
 *    XML. The plugin packs every string, plural and array of one qualified directory into a single `.cvr`
 *    file and has each accessor point at a byte range inside it, so reading one string never parses a
 *    document.
 *
 * The `.cvr` format, which this writes and `components-resources` reads:
 *
 * ```
 * version:0
 * plurals|item_count|ONE:<base64>,OTHER:<base64>
 * string|projects|<base64>
 * string-array|weekdays|<base64>,<base64>
 * ```
 *
 * One record per line, values base64-encoded UTF-8, and a `ResourceItem`'s offset and size span the whole
 * record line rather than just its value.
 *
 * Escapes follow the plugin's `handleSpecialCharacters` exactly: `\n`, `\t`, `\uXXXX` and `\\` are
 * unescaped, and nothing else is. Android's `\'` and `\"` are NOT, so a `\'` in a `strings.xml` reaches the
 * UI as a literal backslash-quote under both builds. Matching that exactly matters more than being right
 * about it, because the strings in this repository are already written for the behaviour.
 *
 * Resource names go through the plugin's `asUnderscoredIdentifier` (see [identifierOf]), so a font file
 * named `IndieFlower-Regular.ttf` is `Res.font.IndieFlower_Regular`, as sources written against the
 * plugin expect.
 */
class ComposeResourcesGenerator(
    private val packageName: String,
    private val publicResClass: Boolean,
) {

    /** What one generation produced, for the build log. */
    data class Result(
        val resourceCount: Int,
        val stagedFiles: Int,
        val generatedFiles: Int,
        val warnings: List<String>,
    )

    private val warnings = ArrayList<String>()

    /**
     * Generate from [roots] (the module's `composeResources` directories, earliest-overridden first) into
     * [kotlinDir] (a generated source root) and [resourcesDir] (a resource root).
     *
     * [sourceSetNameOf] names the `.cvr` a root's values land in (`strings.commonMain.cvr`), which keeps two
     * roots' values files from colliding in the same qualified directory and matches the plugin's names.
     */
    fun generate(
        roots: List<Path>,
        kotlinDir: Path,
        resourcesDir: Path,
        sourceSetNameOf: (Path) -> String = { it.parent?.name ?: "commonMain" },
    ): Result {
        warnings.clear()
        val resources = LinkedHashMap<ResourceId, MutableList<Item>>()
        var staged = 0

        val stageRoot = resourcesDir.resolve(ComposeResourcesFacet.RESOURCE_DIR).resolve(packageName)
        for (root in roots.filter { Files.isDirectory(it) }) {
            val sourceSet = sourceSetNameOf(root)
            for (dir in listVisible(root).filter { Files.isDirectory(it) }) {
                val (type, qualifiers) = parseQualifiedDir(dir.name)
                val outDir = stageRoot.resolve(dir.name)
                if (type == VALUES) {
                    staged += stageValues(dir, outDir, sourceSet, qualifiers, resources)
                } else {
                    staged += stageFiles(dir, outDir, type, qualifiers, resources)
                }
            }
        }

        val generated = writeKotlin(kotlinDir, resources)
        return Result(resources.size, staged, generated, warnings.toList())
    }

    // -----------------------------------------------------------------------------------------------
    // Staging
    // -----------------------------------------------------------------------------------------------

    /** A `drawable*`/`font*`/`files*` directory: copy each file, and record a typed accessor for it. */
    private fun stageFiles(
        dir: Path,
        outDir: Path,
        type: String,
        qualifiers: List<String>,
        resources: MutableMap<ResourceId, MutableList<Item>>,
    ): Int {
        var count = 0
        Files.createDirectories(outDir)
        for (file in listVisible(dir).filter { Files.isRegularFile(it) }) {
            Files.copy(file, outDir.resolve(file.name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            count++
            // `files/` is the untyped bucket: reachable through Res.readBytes(path), with no accessor.
            if (type !in ACCESSOR_TYPES) continue
            val id = ResourceId(type, identifierOf(file.name.substringBeforeLast('.')))
            resources.getOrPut(id) { ArrayList() }
                .add(Item(qualifiers, "${dir.name}/${file.name}", offset = -1, size = -1))
        }
        return count
    }

    /** A `values*` directory: every `.xml` in it becomes records in one `.cvr`. */
    private fun stageValues(
        dir: Path,
        outDir: Path,
        sourceSet: String,
        qualifiers: List<String>,
        resources: MutableMap<ResourceId, MutableList<Item>>,
    ): Int {
        val records = ArrayList<Record>()
        for (xml in listVisible(dir).filter { it.extension.equals("xml", ignoreCase = true) }) {
            records += ValuesXml.parse(xml.readText(), xml.name) { warnings += it }
        }
        if (records.isEmpty()) return 0

        // Deterministic order (type, then key): the offsets below are computed from it, so the file and the
        // accessors pointing into it can only agree if the order is fixed.
        records.sortWith(compareBy({ it.type }, { it.key }))

        val fileName = "strings.$sourceSet.cvr"
        val bytes = StringBuilder()
        var offset = VERSION_LINE.toByteArray(Charsets.UTF_8).size + 1
        bytes.append(VERSION_LINE).append('\n')
        for (record in records) {
            val line = "${record.type}|${record.key}|${record.payload}"
            val size = line.toByteArray(Charsets.UTF_8).size
            bytes.append(line).append('\n')
            resources.getOrPut(ResourceId(record.type, identifierOf(record.key))) { ArrayList() }
                .add(Item(qualifiers, "${dir.name}/$fileName", offset, size))
            offset += size + 1
        }
        Files.createDirectories(outDir)
        outDir.resolve(fileName).writeText(bytes.toString())
        return 1
    }

    /**
     * Split a qualified directory name (`values-pt-rBR`, `drawable-hdpi`) into its resource type and the
     * qualifier expressions the generated accessors carry. An unrecognized qualifier is dropped with a
     * warning rather than guessed at: a wrong qualifier silently picks the wrong file at runtime.
     */
    private fun parseQualifiedDir(name: String): Pair<String, List<String>> {
        val parts = name.split('-')
        val type = parts.first().lowercase()
        val qualifiers = parts.drop(1).flatMap { part ->
            when {
                part in DENSITIES -> listOf("DensityQualifier.${part.uppercase()}")
                part == "light" || part == "dark" -> listOf("ThemeQualifier.${part.uppercase()}")
                // `b+sr+Latn+RS`: a BCP 47 tag, language then optional script and region.
                part.startsWith("b+") -> part.removePrefix("b+").split('+').mapIndexedNotNull { i, tag ->
                    when {
                        i == 0 && LANGUAGE.matches(tag) -> "LanguageQualifier(\"$tag\")"
                        SCRIPT.matches(tag) -> "ScriptQualifier(\"$tag\")"
                        BCP_REGION.matches(tag) -> "RegionQualifier(\"$tag\")"
                        else -> {
                            warnings += "compose resources: ignoring malformed BCP 47 subtag '$tag' in '$name'"
                            null
                        }
                    }
                }
                REGION.matches(part) -> listOf("RegionQualifier(\"${part.takeLast(2)}\")")
                LANGUAGE.matches(part) -> listOf("LanguageQualifier(\"$part\")")
                else -> {
                    warnings += "compose resources: ignoring unrecognized qualifier '$part' in '$name'"
                    emptyList()
                }
            }
        }
        return type to qualifiers
    }

    // -----------------------------------------------------------------------------------------------
    // Kotlin generation
    // -----------------------------------------------------------------------------------------------

    private fun writeKotlin(kotlinDir: Path, resources: Map<ResourceId, List<Item>>): Int {
        val dir = kotlinDir.resolve(packageName.replace('.', '/'))
        Files.createDirectories(dir)
        val byType = resources.entries.groupBy({ it.key.type }, { it.key.key to it.value })
        dir.resolve("Res.kt").writeText(resClass(byType.keys))
        var files = 1
        for ((type, entries) in byType) {
            if (type !in ACCESSOR_TYPES) continue
            dir.resolve("${accessorOf(type)}Resources.kt").writeText(accessors(type, entries))
            files++
        }
        return files
    }

    private val visibility get() = if (publicResClass) "public" else "internal"

    private fun resClass(types: Set<String>): String = buildString {
        appendLine("@file:OptIn(InternalResourceApi::class)")
        appendLine()
        appendLine("package $packageName")
        appendLine()
        appendLine("import org.jetbrains.compose.resources.DrawableResource")
        appendLine("import org.jetbrains.compose.resources.FontResource")
        appendLine("import org.jetbrains.compose.resources.InternalResourceApi")
        appendLine("import org.jetbrains.compose.resources.PluralStringResource")
        appendLine("import org.jetbrains.compose.resources.StringArrayResource")
        appendLine("import org.jetbrains.compose.resources.StringResource")
        appendLine("import org.jetbrains.compose.resources.getResourceUri")
        appendLine("import org.jetbrains.compose.resources.readResourceBytes")
        appendLine()
        appendLine("// Generated by CodeAssist from the module's composeResources roots. Do not edit.")
        appendLine("$visibility object Res {")
        appendLine("    $visibility suspend fun readBytes(path: String): ByteArray =")
        appendLine("        readResourceBytes(\"$MD\" + path)")
        appendLine()
        appendLine("    $visibility fun getUri(path: String): String = getResourceUri(\"$MD\" + path)")
        // The nested objects the accessors extend. Always all five, as the plugin emits them, so a module
        // that has no fonts today still compiles a `Res.font` reference written for one it will add.
        for (type in ACCESSOR_TYPES) {
            appendLine()
            appendLine("    $visibility object ${accessorOf(type).replaceFirstChar { it.lowercase() }}")
        }
        appendLine("}")
        // Types with no resources still need their `all…Resources` map to exist, for the same reason. A
        // getter, not an initializer: an extension property has no backing field to initialize.
        for (type in ACCESSOR_TYPES.filter { it !in types }) {
            appendLine()
            appendLine("$visibility val Res.${allName(type)}: Map<String, ${resourceType(type)}>")
            appendLine("    get() = emptyMap()")
        }
    }

    private fun accessors(type: String, entries: List<Pair<String, List<Item>>>): String = buildString {
        val resourceType = resourceType(type)
        appendLine("@file:OptIn(InternalResourceApi::class)")
        appendLine()
        appendLine("package $packageName")
        appendLine()
        // Only the qualifier classes this file uses: `ScriptQualifier` exists only in newer components-resources,
        // and importing a class the module's version lacks fails the compile even when nothing uses it.
        val qualifierClasses = entries.flatMap { (_, items) -> items.flatMap { it.qualifiers } }
            .map { it.substringBefore('.').substringBefore('(') }
            .toSortedSet()
        val imports = (qualifierClasses + listOf("InternalResourceApi", "ResourceItem", resourceType)).toSortedSet()
        imports.forEach { appendLine("import org.jetbrains.compose.resources.$it") }
        appendLine()
        appendLine("// Generated by CodeAssist from the module's composeResources roots. Do not edit.")
        appendLine("private const val MD: String = \"$MD\"")
        val accessor = accessorOf(type).replaceFirstChar { it.lowercase() }
        for ((key, items) in entries.sortedBy { it.first }) {
            appendLine()
            appendLine("$visibility val Res.$accessor.${escapeKey(key)}: $resourceType by lazy {")
            // The plugin passes the key twice for the value types (id plus the lookup name) and once for
            // the file types, which have no name to look up by.
            val ctorArgs = if (type in NAMED_TYPES) "\"$type:$key\", \"$key\"" else "\"$type:$key\""
            appendLine("    $resourceType($ctorArgs, setOf(")
            for (item in items) {
                val qualifiers = item.qualifiers.joinToString(", ")
                appendLine("        ResourceItem(setOf($qualifiers), \"\${MD}${item.path}\", ${item.offset}, ${item.size}),")
            }
            appendLine("    ))")
            appendLine("}")
        }
        appendLine()
        appendLine("$visibility val Res.${allName(type)}: Map<String, $resourceType> by lazy {")
        appendLine("    mapOf(")
        for ((key, _) in entries.sortedBy { it.first }) {
            appendLine("        \"$key\" to Res.$accessor.${escapeKey(key)},")
        }
        appendLine("    )")
        appendLine("}")
    }

    private val MD get() = "${ComposeResourcesFacet.RESOURCE_DIR}/$packageName/"

    /** A resource key that collides with a Kotlin keyword still has to be addressable. */
    private fun escapeKey(key: String): String = if (key in KOTLIN_KEYWORDS) "`$key`" else key

    /** Files and directories the plugin sees: sorted, hidden ones (`.DS_Store`) skipped as it skips them. */
    private fun listVisible(dir: Path): List<Path> = Files.list(dir).use { stream ->
        stream.filter { !it.name.startsWith('.') && !Files.isHidden(it) }.sorted().collect(Collectors.toList())
    }

    private fun accessorOf(type: String): String = when (type) {
        "string-array" -> "Array"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun resourceType(type: String): String = when (type) {
        "string" -> "StringResource"
        "string-array" -> "StringArrayResource"
        "plurals" -> "PluralStringResource"
        "drawable" -> "DrawableResource"
        "font" -> "FontResource"
        else -> "DrawableResource"
    }

    private fun allName(type: String): String = "all${resourceType(type)}s"

    private data class ResourceId(val type: String, val key: String)

    private data class Item(val qualifiers: List<String>, val path: String, val offset: Int, val size: Int)

    /** One `.cvr` record: a value resource with its payload already base64-encoded. */
    internal data class Record(val type: String, val key: String, val payload: String)

    private companion object {
        const val VALUES = "values"
        const val VERSION_LINE = "version:0"

        /** The types that get a typed accessor. `files` is deliberately absent: it is read by path. */
        val ACCESSOR_TYPES = listOf("drawable", "string", "string-array", "plurals", "font")

        /** Value types, whose constructor takes the lookup key as well as the id. */
        val NAMED_TYPES = setOf("string", "string-array", "plurals")

        val DENSITIES = setOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val LANGUAGE = Regex("[a-z]{2,3}")
        val REGION = Regex("r[A-Z]{2}|r[0-9]{3}")
        val SCRIPT = Regex("[A-Z][a-z]{3}")
        val BCP_REGION = Regex("[A-Z]{2}|[0-9]{3}")

        val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "when", "while",
        )
    }
}

/**
 * The Kotlin name of a resource, as the Compose plugin's `asUnderscoredIdentifier` derives it: `-` becomes
 * `_` and a leading digit gets a `_` prefix, so `IndieFlower-Regular.ttf` is `Res.font.IndieFlower_Regular`
 * and `2048-logo.png` is `Res.drawable._2048_logo`.
 *
 * Any other character Kotlin cannot take in a name (a `.` or a space in a file name) also becomes `_`. The
 * plugin passes those through and generates code that does not compile, so no source can depend on what it
 * would have done; failing a user's build over a file name helps nobody.
 */
internal fun identifierOf(name: String): String {
    val underscored = buildString(name.length) {
        for (c in name) append(if (c == '_' || c.isLetterOrDigit()) c else '_')
    }
    return if (underscored.firstOrNull()?.isDigit() == true) "_$underscored" else underscored
}

/**
 * The subset of an Android `res/values` document Compose resources understands: `<string>`, `<string-array>`
 * and `<plurals>`.
 *
 * Hand-rolled rather than run through a DOM parser because this module has no XML dependency and because
 * the shape is fixed: the value of an element is what the plugin's DOM `textContent` gives (markup dropped,
 * its text kept, CDATA unwrapped, entities resolved), then the plugin's own escape handling
 * ([ComposeResourcesGenerator]).
 */
internal object ValuesXml {

    private val ELEMENT = Regex(
        """<(string|string-array|plurals)\s+name\s*=\s*"([^"]+)"[^>]*?(/>|>(.*?)</\1>)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
    private val ITEM = Regex("""<item(?:\s+quantity\s*=\s*"([^"]+)")?[^>]*?(?:/>|>(.*?)</item>)""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun parse(
        xml: String,
        fileName: String,
        onWarning: (String) -> Unit,
    ): List<ComposeResourcesGenerator.Record> {
        val body = COMMENT.replace(xml, "")
        val out = ArrayList<ComposeResourcesGenerator.Record>()
        for (match in ELEMENT.findAll(body)) {
            val type = match.groupValues[1]
            val key = match.groupValues[2]
            val inner = match.groupValues[4]
            when (type) {
                "string" -> out += record(type, key, encode(text(inner)))
                "string-array" -> out += record(type, key, items(inner).joinToString(",") { encode(it.second) })
                "plurals" -> {
                    val quantities = items(inner).mapNotNull { (quantity, value) ->
                        if (quantity == null) {
                            onWarning("compose resources: <item> without a quantity in $fileName/$key")
                            null
                        } else "${quantity.uppercase()}:${encode(value)}"
                    }
                    out += record(type, key, quantities.joinToString(","))
                }
            }
        }
        return out
    }

    private fun record(type: String, key: String, payload: String) =
        ComposeResourcesGenerator.Record(type, key, payload)

    private fun items(inner: String): List<Pair<String?, String>> =
        ITEM.findAll(inner).map { it.groupValues[1].ifEmpty { null } to text(it.groupValues[2]) }.toList()

    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|quot|apos|amp);")

    /**
     * Inner text as DOM `textContent` reads it: CDATA verbatim, and outside it markup dropped (the text of a
     * `<b>` or `<xliff:g>` kept) and entities resolved. Then the plugin's escapes.
     */
    internal fun text(raw: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in CDATA.findAll(raw)) {
            out.append(markupText(raw.substring(last, m.range.first)))
            out.append(m.groupValues[1])
            last = m.range.last + 1
        }
        out.append(markupText(raw.substring(last)))
        return handleSpecialCharacters(out.toString())
    }

    private fun markupText(part: String): String = ENTITY.replace(TAG.replace(part, "")) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else -> {
                val code = if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()
                String(Character.toChars(code))
            }
        }
    }

    /**
     * The plugin's `handleSpecialCharacters`, rule for rule: `\n`, `\t` and `\uXXXX` are unescaped unless the
     * backslash is itself escaped, then every `\\` becomes `\`. Nothing else (`\'`, `\"`, `\@`) is touched.
     */
    internal fun handleSpecialCharacters(string: String): String {
        val doubleSlash = Regex("""\\\\""").findAll(string).map { it.range.first }.toSet()
        return Regex("""\\u[a-fA-F\d]{4}|\\n|\\t""").replace(string) { m ->
            if (m.range.first - 1 in doubleSlash) m.value
            else when (m.value) {
                "\\n" -> "\n"
                "\\t" -> "\t"
                else -> m.value.substring(2).toInt(16).toChar().toString()
            }
        }.replace("\\\\", "\\")
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
