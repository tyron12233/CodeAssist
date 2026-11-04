package dev.codeassist.dart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * A Flutter app's asset bundle (`flutter_assets`), built the way `flutter build bundle` lays one out, for the
 * player to run the app with: the assets and fonts the app and its packages declare, the manifests the
 * framework reads them by, and the base assets (the Material icons font, the framework's compiled shaders,
 * the license notices) that come with the Flutter the plugin carries.
 *
 * Not implemented: resolution-aware asset variants (`2.0x/`), asset transformers, deferred components.
 */
object FlutterAssets {

    /** The bundle: published path to the file it comes from, plus the generated manifests. */
    class Bundle(val files: Map<String, Path>, val generated: Map<String, ByteArray>)

    /** Build the bundle of the package at [pkg], whose `pub get` has run, with the base assets in [base]. */
    fun build(pkg: Path, base: Path): Bundle {
        val files = LinkedHashMap<String, Path>()
        val assetKeys = ArrayList<String>()
        val fonts = ArrayList<JsonObject>()

        val packages = packages(pkg)
        val rootName = DartPackages.nameOf(read(pkg.resolve(DartPackages.PUBSPEC))) ?: ""
        // The app first, then its packages: the order flutter_tools lists them in.
        val ordered = packages.sortedBy { (name, _) -> if (name == rootName) 0 else 1 }
        var materialDesign = false
        for ((name, dir) in ordered) {
            val isRoot = name == rootName
            val section = (MiniYaml.parse(read(dir.resolve(DartPackages.PUBSPEC))) as? Map<*, *>)?.get("flutter") as? Map<*, *>
                ?: continue
            val prefix = if (isRoot) "" else "packages/$name/"
            if (isRoot && section["uses-material-design"]?.toString() == "true") materialDesign = true
            for (entry in section["assets"] as? List<*> ?: emptyList<Any>()) {
                val declared = (entry as? Map<*, *>)?.get("path")?.toString() ?: entry?.toString() ?: continue
                for (file in expand(dir, declared)) {
                    val key = prefix + dir.relativize(file).joinToString("/") { it.toString() }
                    if (files.put(key, file) == null) assetKeys += key
                }
            }
            for (family in section["fonts"] as? List<*> ?: emptyList<Any>()) {
                val f = family as? Map<*, *> ?: continue
                val familyName = f["family"]?.toString() ?: continue
                val faces = (f["fonts"] as? List<*>).orEmpty().mapNotNull { face ->
                    val m = face as? Map<*, *> ?: return@mapNotNull null
                    val asset = m["asset"]?.toString() ?: return@mapNotNull null
                    val key = prefix + asset
                    val file = dir.resolve(asset).normalize()
                    if (Files.isRegularFile(file) && files.put(key, file) == null) assetKeys += key
                    buildJsonObject {
                        put("asset", key)
                        m["weight"]?.toString()?.toIntOrNull()?.let { put("weight", it) }
                        m["style"]?.toString()?.let { put("style", it) }
                    }
                }
                fonts += buildJsonObject {
                    put("family", prefix + familyName)
                    put("fonts", JsonArray(faces))
                }
            }
        }

        if (materialDesign) {
            files["fonts/MaterialIcons-Regular.otf"] = base.resolve("fonts/MaterialIcons-Regular.otf")
            fonts.add(0, buildJsonObject {
                put("family", "MaterialIcons")
                put("fonts", buildJsonArray { add(buildJsonObject { put("asset", "fonts/MaterialIcons-Regular.otf") }) })
            })
        }
        listFiles(base.resolve("shaders")).forEach { files["shaders/${it.fileName}"] = it }
        base.resolve("NOTICES.Z").takeIf { Files.isRegularFile(it) }?.let { files["NOTICES.Z"] = it }

        val generated = linkedMapOf(
            "AssetManifest.bin" to assetManifest(assetKeys),
            "FontManifest.json" to Json.encodeToString(JsonArray.serializer(), JsonArray(fonts)).toByteArray(),
            "NativeAssetsManifest.json" to """{"format-version":[1,0,0],"native-assets":{}}""".toByteArray(),
        )
        return Bundle(files, generated)
    }

    /** The asset manifest: each asset to its variants, the only variant being itself (no `2.0x/` support). */
    fun assetManifest(keys: List<String>): ByteArray =
        StandardMessageCodec.encode(keys.associateWith { listOf(mapOf("asset" to it)) })

    /** A declared asset: a file, or a directory (ending in `/`) meaning the files directly in it. */
    private fun expand(dir: Path, declared: String): List<Path> {
        val target = dir.resolve(declared.removeSuffix("/")).normalize()
        if (!target.startsWith(dir)) return emptyList()
        return if (declared.endsWith("/")) listFiles(target) else listOfNotNull(target.takeIf { Files.isRegularFile(it) })
    }

    private fun listFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }.collect(Collectors.toList()) }
            .sortedBy { it.fileName.toString() }
    }

    /** Package name to root directory, from `.dart_tool/package_config.json`. */
    fun packages(pkg: Path): List<Pair<String, Path>> {
        val configFile = pkg.resolve(DartPackages.PACKAGE_CONFIG)
        val config = runCatching { Json.parseToJsonElement(read(configFile)).jsonObject }.getOrNull() ?: return emptyList()
        val base = configFile.parent.toUri()
        return (config["packages"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val rootUri = (o["rootUri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val resolved = base.resolve(URI(rootUri.let { if (it.endsWith("/")) it else "$it/" }))
            name to Paths.get(resolved).normalize()
        }
    }

    private fun read(path: Path): String = runCatching { String(Files.readAllBytes(path)) }.getOrDefault("")
}

/**
 * Flutter's StandardMessageCodec, the encoding of `AssetManifest.bin`: the part of it a manifest uses (null,
 * booleans, strings, lists and maps). Sizes are one byte below 254, else a marker and two or four bytes,
 * little-endian.
 */
object StandardMessageCodec {
    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0)
            true -> out.write(1)
            false -> out.write(2)
            is String -> {
                out.write(7)
                val bytes = value.toByteArray(Charsets.UTF_8)
                size(out, bytes.size)
                out.write(bytes)
            }
            is List<*> -> { out.write(12); size(out, value.size); value.forEach { write(out, it) } }
            is Map<*, *> -> { out.write(13); size(out, value.size); value.forEach { (k, v) -> write(out, k); write(out, v) } }
            else -> error("StandardMessageCodec: cannot encode ${value::class.simpleName}")
        }
    }

    private fun size(out: ByteArrayOutputStream, n: Int) {
        when {
            n < 254 -> out.write(n)
            n <= 0xffff -> { out.write(254); out.write(n and 0xff); out.write(n ushr 8) }
            else -> { out.write(255); for (shift in 0..24 step 8) out.write((n ushr shift) and 0xff) }
        }
    }
}

/**
 * Enough YAML for a pubspec: block mappings and sequences by indentation, scalars (quoted or not), comments.
 * A sequence item that starts a mapping (`- family: Roboto` with more keys under it) is a mapping. Not flow
 * collections, anchors or multi-line scalars, which no pubspec section this plugin reads uses.
 */
object MiniYaml {
    private class Line(val indent: Int, val text: String)

    fun parse(text: String): Any? {
        val lines = text.lineSequence().mapNotNull { raw ->
            val stripped = stripComment(raw).trimEnd()
            if (stripped.isBlank() || stripped.trim() == "---") null
            else Line(stripped.length - stripped.trimStart().length, stripped.trim())
        }.toMutableList()
        if (lines.isEmpty()) return null
        return Parser(lines).block(lines[0].indent)
    }

    private class Parser(val lines: MutableList<Line>) {
        var i = 0

        fun block(indent: Int): Any? {
            if (i >= lines.size) return null
            return if (lines[i].text == "-" || lines[i].text.startsWith("- ")) sequence(indent) else mapping(indent)
        }

        fun mapping(indent: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (i < lines.size && lines[i].indent == indent && !isItem(lines[i].text)) {
                val line = lines[i]
                val colon = keyColon(line.text)
                if (colon < 0) { i++; continue }
                val key = unquote(line.text.substring(0, colon).trim())
                val rest = line.text.substring(colon + 1).trim()
                i++
                out[key] = if (rest.isNotEmpty()) scalar(rest) else when {
                    i < lines.size && lines[i].indent > indent -> block(lines[i].indent)
                    // `key:` followed by a sequence at the same indent is legal YAML, and common in pubspecs.
                    i < lines.size && lines[i].indent == indent && isItem(lines[i].text) -> sequence(indent)
                    else -> null
                }
            }
            return out
        }

        fun sequence(indent: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (i < lines.size && lines[i].indent == indent && isItem(lines[i].text)) {
                val content = lines[i].text.removePrefix("-").trimStart()
                if (content.isEmpty()) {
                    i++
                    out += if (i < lines.size && lines[i].indent > indent) block(lines[i].indent) else null
                } else if (keyColon(content) >= 0) {
                    // `- key: value` starts a mapping whose keys sit where `key` does.
                    val inner = indent + (lines[i].text.length - content.length)
                    lines[i] = Line(inner, content)
                    out += mapping(inner)
                } else {
                    out += scalar(content)
                    i++
                }
            }
            return out
        }

        private fun isItem(text: String) = text == "-" || text.startsWith("- ")
    }

    /** The colon ending a mapping key on [text], ignoring colons inside quotes; -1 for none. */
    private fun keyColon(text: String): Int {
        var quote: Char? = null
        for ((index, c) in text.withIndex()) {
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') { if (index == 0) quote = c; continue }
            if (c == ':' && (index + 1 == text.length || text[index + 1] == ' ')) return index
        }
        return -1
    }

    private fun scalar(text: String): String = unquote(text)

    private fun unquote(text: String): String =
        if (text.length >= 2 && (text[0] == '"' || text[0] == '\'') && text.last() == text[0]) text.substring(1, text.length - 1) else text

    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((index, c) in line.withIndex()) {
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') quote = c
            else if (c == '#' && (index == 0 || line[index - 1].isWhitespace())) return line.substring(0, index)
        }
        return line
    }
}
