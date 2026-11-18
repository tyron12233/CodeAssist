package dev.codeassist.csharp.project.nuget

import android.util.Log
import dev.codeassist.csharp.util.TAG
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Restores NuGet packages for a project.
 *
 * Packages are downloaded from nuget.org, unpacked under [root], and exposed to the compiler
 * as DLLs.
 *
 * Uses the NuGet Package Base Address resource:
 * https://learn.microsoft.com/nuget/api/package-base-address-resource
 *
 * The base URL comes from the service index. A package is located at
 * `{base}/{id}/{version}/{id}.{version}.nupkg`, and its available versions at
 * `{base}/{id}/index.json`. Package IDs and versions are lowercase, with versions normalized.
 *
 * Restores the DLLs from the best compatible target framework of each package, along with
 * its dependencies. When multiple versions of a package are requested, the highest requested
 * version wins.
 *
 * Not supported: native libraries, analyzers, source generators, `PrivateAssets`, signature
 * verification, and package sources other than nuget.org.
 *
 * Packages whose DLLs are already provided by the SDK are skipped. For example, restoring
 * `System.Memory` separately could introduce duplicate types.
 *
 * Package layout under [root]:
 * - `<id>/<version>/dll/` contains the selected framework's DLLs and XML documentation.
 * - `<id>/<version>/package.nuspec` contains package metadata.
 * - `<id>/<version>/.ok` marks a completed extraction.
 *
 * The `.ok` marker is written last, so an interrupted download or extraction is retried.
 *
 * No `.deps.json` is written for a program: a package's DLLs are placed beside it, as ordinary
 * `<Reference>` assemblies are.
 */
internal class NuGet(
    private val root: Path,
    private val fetch: Fetch = HttpFetch,
) {

    /** Fetches bytes from a URL, or returns null when unavailable. */
    fun interface Fetch {
        fun get(url: String): ByteArray?
    }

    /** A package ID and the version specification from the project file. */
    class Request(val id: String, val version: String?)

    /** DLLs available for compilation and any packages that could not be restored. */
    class Result(val dlls: List<Path>, val problems: List<String>)

    private var baseUrl: String? = null

    /** Downloads missing packages, then returns the same result as [cached]. */
    @Synchronized
    fun restore(
        requests: List<Request>,
        exclude: Set<String>,
        isCancelled: () -> Boolean = { false },
    ): Result = walk(requests, exclude, online = true, isCancelled)

    /** Returns DLLs from packages already unpacked. Missing packages are silently skipped. */
    @Synchronized
    fun cached(requests: List<Request>, exclude: Set<String>): Result =
    walk(requests, exclude, false) { false }

    /** Returns true when every requested package and its dependencies are already unpacked. */
    @Synchronized
    fun isRestored(requests: List<Request>, exclude: Set<String>): Boolean {
        val chosen = LinkedHashMap<String, NuGetVersion>()

        return walkGraph(requests, exclude, online = false, { false }, chosen, ArrayList()) &&
        requests.all { it.id.lowercase() in chosen || skipped(it.id.lowercase(), exclude) }
    }

    // Package graph traversal

    /**
     * Resolves the requested packages and collects their DLLs.
     *
     * @param online whether missing packages may be downloaded
     * @param isCancelled checked between packages
     */
    private fun walk(
        requests: List<Request>,
        exclude: Set<String>,
        online: Boolean,
        isCancelled: () -> Boolean,
    ): Result {
        val chosen = LinkedHashMap<String, NuGetVersion>()
        val problems = ArrayList<String>()

        walkGraph(requests, exclude, online, isCancelled, chosen, problems)

        val dlls = chosen.flatMap { (key, version) ->
            val dir = packageDir(key, version).resolve(DLLS)
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.list(dir).use { stream ->
                    // Not Stream.toList(): it is a Java 16 method, absent before Android 14.
                    stream.iterator().asSequence().filter {
                        it.fileName.toString().lowercase().endsWith(".dll")
                    }.toList()
                }
            }
        }
        .filter { it.fileName.toString().lowercase() !in exclude }
        .sortedBy { it.fileName.toString() }

        return Result(dlls, problems)
    }

    /**
     * Traverses requested packages and their dependencies, filling [chosen] with the versions
     * to use.
     *
     * Returns false if traversal stopped because [isCancelled] returned true.
     */
    private fun walkGraph(
        requests: List<Request>,
        exclude: Set<String>,
        online: Boolean,
        isCancelled: () -> Boolean,
        chosen: MutableMap<String, NuGetVersion>,
        problems: MutableList<String>,
    ): Boolean {
        val queue = ArrayDeque(requests.map { it.id to it.version })

        while (queue.isNotEmpty()) {
            if (isCancelled()) return false

            val (id, spec) = queue.removeFirst()
            val key = id.lowercase()

            // The id becomes a directory name and part of a URL. It comes from a project file or from a
            // downloaded package's own dependency list, so one that is not a package id is refused here.
            if (!PACKAGE_ID.matches(key)) {
                if (online) problems += "NuGet package $id: not a valid package id"
                continue
            }

            if (skipped(key, exclude)) continue

            val version = pick(key, spec, online)?.takeIf { VERSION_TEXT.matches(it.normalized) }
            if (version == null) {
                if (online) {
                    problems += "NuGet package $id${spec?.let { " $it" }.orEmpty()}: no version found"
                }
                continue
            }

            val known = chosen[key]
            if (known != null && known >= version) continue

            val dir = packageDir(key, version)
            if (!Files.isRegularFile(dir.resolve(DONE))) {
                if (!online) continue

                val error = download(key, version, dir)
                if (error != null) {
                    problems += "NuGet package $id ${version.normalized}: $error"
                    continue
                }
            }

            chosen[key] = version

            val nuspec = dir.resolve(NUSPEC)
            if (Files.isRegularFile(nuspec)) {
                queue.addAll(
                    dependenciesOf(String(Files.readAllBytes(nuspec), Charsets.UTF_8))
                )
            }
        }

        return true
    }

    // Version selection and cache lookup

    /** Returns the version to use: the lowest allowed version, or the newest stable floating match. */
    private fun pick(key: String, spec: String?, online: Boolean): NuGetVersion? {
        val text = spec?.trim().orEmpty()

        if (text.isNotEmpty() && !NuGetVersion.isFloating(text)) {
            return NuGetVersion.lowerBound(text)
        }

        val prefix = if (text.isEmpty()) "" else NuGetVersion.floatingPrefix(text)
        val known = (if (online) remoteVersions(key) else null) ?: cachedVersions(key)

        return known.mapNotNull { NuGetVersion.parse(it) }
        .filter { !it.isPrerelease && it.normalized.startsWith(prefix) }
        .maxOrNull()
    }

    /** Returns versions listed by nuget.org, or null if the request or response cannot be read. */
    private fun remoteVersions(key: String): List<String>? {
        val json = fetch.get("${base()}$key/index.json")?.toString(Charsets.UTF_8)
        ?: return null

        val list = VERSIONS.find(json)?.groupValues?.get(1) ?: return null
        return QUOTED.findAll(list).map { it.groupValues[1] }.toList()
    }

    /** Returns versions with a completed extraction in the local cache. */
    private fun cachedVersions(key: String): List<String> {
        val dir = root.resolve(key)
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.iterator().asSequence()
            .filter { Files.isRegularFile(it.resolve(DONE)) }
            .map { it.fileName.toString() }
            .toList()
        }
    }

    /** Returns the package directory for [key] and [version]. */
    private fun packageDir(key: String, version: NuGetVersion): Path =
    root.resolve(key).resolve(version.normalized)

    /** Whether a package is already provided by the framework or explicitly excluded. */
    private fun skipped(key: String, exclude: Set<String>): Boolean =
    key in FRAMEWORK_PACKAGES || "$key.dll" in exclude

    // Download and extraction

    /** Resolves the package base address from the service index, caching it after the first lookup. */
    private fun base(): String {
        baseUrl?.let { return it }

        val index = fetch.get(SERVICE_INDEX)?.toString(Charsets.UTF_8)
        val found = index?.let { BASE_ADDRESS.find(it)?.groupValues?.get(1) } ?: FALLBACK_BASE

        return (if (found.endsWith("/")) found else "$found/")
        .also { baseUrl = it }
    }

    /** Downloads and unpacks a package. Returns an error message, or null on success. */
    private fun download(key: String, version: NuGetVersion, dir: Path): String? {
        val name = version.normalized
        Log.d(TAG, "nuget: downloading $key $name")

        val bytes = fetch.get("${base()}$key/$name/$key.$name.nupkg")
        ?: return "could not be downloaded (offline, or there is no such package)"

        return try {
            unpack(bytes, dir)
        } catch (e: Exception) {
            Log.d(TAG, "nuget: unpacking $key $name failed: $e")
            "could not be unpacked: ${e.message}"
        }
    }

    /** Copies a package entry to the destination, replacing any existing file. */
    private fun copy(from: java.io.InputStream, to: Path) {
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Extracts the DLLs and XML documentation from the best compatible framework folder,
     * along with the `.nuspec` file.
     *
     * Returns an error message, or null on success.
     */
    private fun unpack(bytes: ByteArray, dir: Path): String? {
        Files.createDirectories(dir)

        val nupkg = dir.resolve("package.nupkg")
        Files.write(nupkg, bytes)

        try {
            ZipFile(nupkg.toFile()).use { zip ->
                val names = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .toList()

                val libraries = names.map { it.split('/') }
                .filter {
                    it.size == 3 &&
                    it[0].equals("lib", ignoreCase = true) &&
                    it[2].lowercase().endsWith(".dll")
                }

                val frameworks = libraries.map { it[1] }.distinct()
                val best = NuGetFrameworks.best(frameworks)

                if (frameworks.isNotEmpty() && best == null) {
                    return "has no library for net10.0 (it has: ${frameworks.joinToString()})"
                }

                val dllDir = dir.resolve(DLLS)
                Files.createDirectories(dllDir)

                for (parts in libraries.filter { it[1] == best }) {
                    val entry = zip.getEntry(parts.joinToString("/")) ?: continue

                    zip.getInputStream(entry).use {
                        copy(it, dllDir.resolve(parts[2]))
                    }

                    // XML documentation beside each DLL, used by the completion popup.
                    val docName = parts.toMutableList().also {
                        it[2] = it[2].dropLast(4) + ".xml"
                    }

                    zip.getEntry(docName.joinToString("/"))?.let { doc ->
                        zip.getInputStream(doc).use {
                            copy(it, dllDir.resolve(docName[2]))
                        }
                    }
                }

                names.firstOrNull { '/' !in it && it.endsWith(".nuspec") }?.let { spec ->
                    zip.getInputStream(zip.getEntry(spec)).use {
                        copy(it, dir.resolve(NUSPEC))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(nupkg)
        }

        Files.write(dir.resolve(DONE), ByteArray(0))
        return null
    }

    companion object {

        // Cache layout

        private const val DLLS = "dll"
        private const val NUSPEC = "package.nuspec"
        private const val DONE = ".ok"

        // NuGet service endpoints

        private const val SERVICE_INDEX = "https://api.nuget.org/v3/index.json"
        private const val FALLBACK_BASE = "https://api.nuget.org/v3-flatcontainer/"

        // Package metadata

        /** Packages supplied by the .NET framework and therefore not downloaded or referenced separately. */
        private val FRAMEWORK_PACKAGES = setOf(
            "netstandard.library",
            "microsoft.netcore.app",
            "microsoft.netcore.platforms",
        )

        private val BASE_ADDRESS =
        Regex("""\"@id\"\s*:\s*\"([^\"]+)\"\s*,\s*\"@type\"\s*:\s*\"PackageBaseAddress/3\.0\.0""")

        /** A package id as nuget.org accepts one: word characters joined by single `.`, `-` or `_`. */
        private val PACKAGE_ID = Regex("""[a-z0-9_]+(?:[._-][a-z0-9_]+)*""")

        /** A normalized version as it appears in a path: digits, dots and a prerelease label. */
        private val VERSION_TEXT = Regex("""[0-9]+(?:\.[0-9]+)*(?:-[0-9a-z.-]+)?""")

        private val VERSIONS = Regex("""\"versions\"\s*:\s*\[([^\]]*)\]""")
        private val QUOTED = Regex("""\"([^\"]+)\"""")

        // Nuspec dependency parsing

        /**
         * Returns the dependencies declared by a `.nuspec` file.
         *
         * Uses the best matching target-framework group, or the group without a target framework.
         * Each result contains the dependency ID and its version range, or null when unspecified.
         */
        fun dependenciesOf(nuspec: String): List<Pair<String, String?>> {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }

            val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(nuspec.toByteArray(Charsets.UTF_8)))

            val container = doc.getElementsByTagName("dependencies").item(0) as? Element
            ?: return emptyList()

            fun children(parent: Element, name: String): List<Element> {
                val nodes = parent.childNodes
                return (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? Element }
                .filter { it.nodeName == name }
            }

            fun dependenciesIn(parent: Element) = children(parent, "dependency")
            .map {
                it.getAttribute("id") to it.getAttribute("version").ifEmpty { null }
            }
            .filter { it.first.isNotEmpty() }

            val groups = children(container, "group")
            if (groups.isEmpty()) return dependenciesIn(container)

            val targeted = groups.filter {
                it.getAttribute("targetFramework").isNotEmpty()
            }

            val best = NuGetFrameworks.best(
                targeted.map { it.getAttribute("targetFramework") }
            )

            val group = targeted.firstOrNull {
                it.getAttribute("targetFramework") == best
            } ?: groups.firstOrNull {
                it.getAttribute("targetFramework").isEmpty()
            } ?: return emptyList()

            return dependenciesIn(group)
        }
    }
}

/** Fetches package data from nuget.org over HTTPS. */
private object HttpFetch : NuGet.Fetch {

    /** Returns response bytes for HTTP 200, or null for other responses and connection failures. */
    override fun get(url: String): ByteArray? = try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection

        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "CodeAssist-CSharp")

        try {
            if (connection.responseCode == 200) {
                connection.inputStream.use { it.readBytes() }
            } else {
                Log.d(TAG, "nuget: GET $url answered ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.d(TAG, "nuget: GET $url failed: $e")
        null
    }
}
