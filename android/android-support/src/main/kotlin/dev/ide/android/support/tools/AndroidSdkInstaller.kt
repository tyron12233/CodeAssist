package dev.ide.android.support.tools

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.stream.Collectors
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A self-contained Android SDK downloader for **sources** and **platforms**, talking to Google's SDK
 * repository directly (so it works on desktop and on-device, where no `sdkmanager` exists). It fetches the
 * repository manifest, lists the installable packages, and installs one by downloading its archive and
 * extracting it into the SDK layout. Network and disk are the only side effects; all I/O goes through an
 * injected [SdkNetFetcher] so the parsing/selection/path logic is unit-testable offline.
 *
 * Sources power editor docs (javadoc, parameter names, go-to-source). Platforms supply the `android.jar` a
 * module's compileSdk compiles against when it is not the bundled one; a [slim][install] platform install
 * fetches only the files the IDE reads ([SLIM_PLATFORM_FILES]). Build-tools and command-line tools are not
 * offered: on-device the build uses bundled tools (aapt2, in-process D8/apksig).
 */
object AndroidSdkInstaller {

    const val REPO_BASE = "https://dl.google.com/android/repository/"
    private const val REPO_XML = "repository2-3.xml"

    /** [PLATFORM_TOOLS] is `platform-tools` (adb), a host binary: only a desktop host can run it. */
    enum class Category { SOURCES, PLATFORMS, PLATFORM_TOOLS, OTHER }

    /** The oldest platform offered; older ones predate every minSdk the IDE builds for. */
    const val MIN_PLATFORM = 21

    /** One installable package from the repository manifest. [path] is the sdkmanager-style id
     *  (`platforms;android-34`); [archiveUrl] is absolute (or null if no archive fits this host); [license]
     *  is the id of the license the user must accept first (`android-sdk-license`). */
    data class RepoPackage(
        val path: String,
        val displayName: String,
        val revision: String,
        val category: Category,
        val archiveUrl: String?,
        val sizeBytes: Long,
        val license: String? = null,
    ) {
        /** Where this package installs under an SDK root: `platforms;android-34` → `platforms/android-34`. */
        fun installDir(sdkRoot: Path): Path = path.split(';').fold(sdkRoot) { acc, seg -> acc.resolve(seg) }
    }

    /** The parsed manifest: its packages and the text of each license they reference, by id. */
    data class Repository(val packages: List<RepoPackage>, val licenses: Map<String, String>)

    /** Fetch + parse the repository manifest into the available packages. Empty on a parse failure. */
    fun fetchPackages(fetcher: SdkNetFetcher = HttpSdkNetFetcher): List<RepoPackage> = fetchRepository(fetcher).packages

    /** Fetch + parse the repository manifest. Throws when it cannot be reached; empty on a parse failure. */
    fun fetchRepository(fetcher: SdkNetFetcher = HttpSdkNetFetcher): Repository {
        val xml = fetcher.fetchText(REPO_BASE + REPO_XML)
            ?: throw IOException("Could not reach the Android SDK repository (${REPO_BASE}${REPO_XML}). Check your internet connection.")
        return runCatching { Repository(parsePackages(xml), parseLicenses(xml)) }.getOrDefault(Repository(emptyList(), emptyMap()))
    }

    internal fun parseLicenses(xml: String): Map<String, String> {
        val doc = parseXml(xml)
        val out = LinkedHashMap<String, String>()
        val licenses = doc.getElementsByTagNameNS("*", "license")
        for (i in 0 until licenses.length) {
            val l = licenses.item(i) as? Element ?: continue
            val id = l.getAttribute("id").ifEmpty { continue }
            out[id] = l.textContent
        }
        return out
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Disable external entity resolution: prevents the parser from opening network connections
            // to resolve DTD/schema URIs embedded in the XML (XXE protection + no surprise DNS).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return dbf.newDocumentBuilder().also { it.setEntityResolver { _, _ -> org.xml.sax.InputSource("".reader()) } }.parse(xml.byteInputStream())
    }

    internal fun parsePackages(xml: String): List<RepoPackage> {
        val doc = parseXml(xml)
        val out = ArrayList<RepoPackage>()
        val remotes = doc.getElementsByTagNameNS("*", "remotePackage")
        for (i in 0 until remotes.length) {
            val pkg = remotes.item(i) as? Element ?: continue
            val path = pkg.getAttribute("path").ifEmpty { continue }
            val category = categoryOf(path)
            if (category == Category.OTHER) continue
            val displayName = childText(pkg, "display-name") ?: path
            val revision = revisionOf(pkg)
            val (url, size) = chooseArchive(pkg)
            val license = firstChild(pkg, "uses-license")?.getAttribute("ref")?.ifEmpty { null }
            out += RepoPackage(path, displayName, revision, category, url?.let { REPO_BASE + it }, size, license)
        }
        return out
    }

    /** sdkmanager-style ids fully installed under [sdkRoot] (so the UI can mark them). A dir left with an
     *  `.installing` marker (an interrupted install) is reported by [incompletePackages] instead. */
    fun installedPackages(sdkRoot: Path): Set<String> = scanPackages(sdkRoot) { dir -> !Files.exists(markerFor(dir)) }

    /** sdkmanager-style ids whose install dir exists but is incomplete (an `.installing` marker survived an
     *  interrupted install). Re-installing resumes the cached download and repairs the dir. */
    fun incompletePackages(sdkRoot: Path): Set<String> = scanPackages(sdkRoot) { dir -> Files.exists(markerFor(dir)) }

    private fun scanPackages(sdkRoot: Path, accept: (Path) -> Boolean): Set<String> {
        val out = HashSet<String>()
        fun listDirs(rel: String, idPrefix: String, complete: (Path) -> Boolean = { true }) {
            val d = sdkRoot.resolve(rel)
            if (!Files.isDirectory(d)) return
            Files.list(d).use { s -> s.filter { Files.isDirectory(it) && accept(it) && complete(it) }.forEach { out += "$idPrefix${it.fileName}" } }
        }
        listDirs("sources", "sources;")
        listDirs("platforms", "platforms;") { dir -> Files.exists(markerFor(dir)) || Files.isRegularFile(dir.resolve("android.jar")) }
        // A single package rather than a directory of them: `platform-tools` itself.
        val tools = sdkRoot.resolve("platform-tools")
        if (Files.isDirectory(tools) && accept(tools) && (Files.exists(markerFor(tools)) || adbIn(tools) != null)) {
            out += "platform-tools"
        }
        return out
    }

    /**
     * The installed platforms under [sdkRoot] by API level: `platforms/android-34/android.jar` → 34. When a
     * level has several revisions (`android-36` and `android-36.1`), the plain one wins, then the newest
     * minor. Interrupted installs are skipped.
     */
    fun installedPlatforms(sdkRoot: Path): Map<Int, Path> {
        val dir = sdkRoot.resolve("platforms")
        if (!Files.isDirectory(dir)) return emptyMap()
        val byLevel = HashMap<Int, Pair<Int, Path>>() // level -> (minor, jar); plain = Int.MAX_VALUE
        Files.list(dir).use { s ->
            s.forEach { d ->
                val m = PLATFORM_DIR.matchEntire(d.fileName.toString()) ?: return@forEach
                val jar = d.resolve("android.jar")
                if (Files.exists(markerFor(d)) || !Files.isRegularFile(jar)) return@forEach
                val level = m.groupValues[1].toInt()
                val rank = m.groupValues[2].ifEmpty { null }?.toInt() ?: Int.MAX_VALUE
                val cur = byLevel[level]
                if (cur == null || rank > cur.first) byLevel[level] = rank to jar
            }
        }
        return byLevel.mapValues { it.value.second }.toSortedMap()
    }

    /** The sibling marker file flagging an install dir as in-progress (created during install, deleted on success). */
    private fun markerFor(installDir: Path): Path =
        installDir.resolveSibling("${installDir.fileName}.installing")

    /**
     * Install [pkg] into [sdkRoot]: download its archive (into [downloadsDir], a stable per-id cache so an
     * interrupted download *resumes* on retry rather than restarting), extract it, and place the contents at
     * the package's install dir. An `.installing` marker guards the dir until the place step completes, so an
     * interrupted install is detectable ([incompletePackages]) and never looks installed. [onProgress] gets
     * (bytesRead, totalBytes) during the download; [onStage] gets DOWNLOADING/EXTRACTING/INSTALLING. Returns
     * null on success, or an error message.
     */
    fun install(
        pkg: RepoPackage,
        sdkRoot: Path,
        downloadsDir: Path,
        fetcher: SdkNetFetcher = HttpSdkNetFetcher,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {},
        /** For a platform: fetch only [SLIM_PLATFORM_FILES] out of the archive (falling back to the whole
         *  archive when the server ignores Range requests). Leaves a platform other SDK tools may find
         *  incomplete, so only for an SDK root the IDE owns. */
        slim: Boolean = false,
    ): String? {
        val url = pkg.archiveUrl ?: return "No archive available for this host."
        Files.createDirectories(downloadsDir)
        if (slim && pkg.category == Category.PLATFORMS) {
            val result = try {
                installSlim(pkg, url, sdkRoot, downloadsDir, fetcher, onProgress, onStage)
            } catch (e: Exception) {
                "Install failed: ${e.message}"
            }
            // FULL_DOWNLOAD: the server cannot serve ranges, so download the whole archive below.
            if (result != FULL_DOWNLOAD) return result
        }
        val archive = downloadsDir.resolve(pkg.path.replace(';', '_').replace('/', '_') + ".zip")
        val installDir = pkg.installDir(sdkRoot)
        val marker = markerFor(installDir)
        try {
            onStage("DOWNLOADING")
            if (!fetcher.download(url, archive, onProgress)) return "Download failed."
            onStage("EXTRACTING")
            val staging = Files.createTempDirectory("sdkpkg-extract")
            try {
                try {
                    extractZip(archive, staging)
                } catch (e: Exception) {
                    runCatching { Files.deleteIfExists(archive) } // corrupt/partial cache → drop so a retry re-downloads clean
                    return "Downloaded archive was incomplete; retry to re-download. (${e.message})"
                }
                onStage("INSTALLING")
                Files.createDirectories(installDir.parent)
                runCatching { if (!Files.exists(marker)) Files.createFile(marker) }
                placeInto(staging, installDir)
                if (pkg.category == Category.PLATFORM_TOOLS) markToolsExecutable(installDir)
                Files.deleteIfExists(marker)
            } finally {
                deleteRecursively(staging)
            }
            runCatching { Files.deleteIfExists(archive) } // installed → drop the cached archive
            return null
        } catch (e: Exception) {
            return "Install failed: ${e.message}"
        }
    }

    /**
     * The slim platform install: list the archive over Range requests, fetch the [SLIM_PLATFORM_FILES]
     * entries into a per-package staging dir under [downloadsDir] (kept across attempts, so a retry skips
     * what already arrived), then place them. Returns null on success, an error message on failure, or
     * [FULL_DOWNLOAD] when the archive cannot be listed over ranges.
     */
    private fun installSlim(
        pkg: RepoPackage, url: String, sdkRoot: Path, downloadsDir: Path, fetcher: SdkNetFetcher,
        onProgress: (Long, Long) -> Unit, onStage: (String) -> Unit,
    ): String? {
        onStage("DOWNLOADING")
        val all = RemoteZip.entries(url, pkg.sizeBytes, fetcher) ?: return FULL_DOWNLOAD
        val wanted = all.filter { e -> !e.name.endsWith("/") && isSlimPlatformFile(e.name.substringAfter('/', "")) }
        if (wanted.none { it.name.endsWith("/android.jar") }) return "The archive has no android.jar."
        val staging = downloadsDir.resolve(pkg.path.replace(';', '_').replace('/', '_') + ".slim")
        RemoteZip.extract(url, wanted, staging, fetcher, onProgress)
        onStage("INSTALLING")
        val installDir = pkg.installDir(sdkRoot)
        val marker = markerFor(installDir)
        Files.createDirectories(installDir.parent)
        runCatching { if (!Files.exists(marker)) Files.createFile(marker) }
        placeInto(staging, installDir)
        Files.deleteIfExists(marker)
        deleteRecursively(staging)
        return null
    }

    private const val FULL_DOWNLOAD = "\u0000full-download"

    /** True for a path inside a platform archive's root dir that a [slim][install] install keeps. */
    internal fun isSlimPlatformFile(relative: String): Boolean =
        relative in SLIM_PLATFORM_FILES || (relative.startsWith("optional/") && relative.count { it == '/' } == 1 &&
            (relative.endsWith(".jar") || relative.endsWith(".json")))

    /**
     * What a slim platform install keeps: the jar compiled against, the API database, the AIDL framework
     * parcelables, the version files, and the `optional/` library jars (`useLibrary`). Leaves out the stub
     * sources, layoutlib data, skins and templates.
     */
    val SLIM_PLATFORM_FILES = setOf(
        "android.jar", "framework.aidl", "build.prop", "source.properties", "sdk.properties", "data/api-versions.xml",
    )

    // ---- XML helpers ----

    /** `platforms;android-34` or `platforms;android-36.1`: stable releases only (no -ext, beta or canary). */
    private val PLATFORM_PATH = Regex("""platforms;android-(\d+)(?:\.\d+)?""")
    private val PLATFORM_DIR = Regex("""android-(\d+)(?:\.(\d+))?""")

    fun categoryOf(path: String): Category = when {
        path.startsWith("sources;") -> Category.SOURCES
        path == "platform-tools" -> Category.PLATFORM_TOOLS
        PLATFORM_PATH.matchEntire(path)?.groupValues?.get(1)?.toInt()?.let { it >= MIN_PLATFORM } == true -> Category.PLATFORMS
        else -> Category.OTHER
    }

    /** The API level of a platform package id (`platforms;android-36.1` → 36), or null. */
    fun platformLevel(path: String): Int? = PLATFORM_PATH.matchEntire(path)?.groupValues?.get(1)?.toInt()

    private fun revisionOf(pkg: Element): String {
        val rev = firstChild(pkg, "revision") ?: return ""
        val parts = listOf("major", "minor", "micro").mapNotNull { childText(rev, it) }
        return parts.joinToString(".")
    }

    /** Pick the archive whose `host-os` matches this host (or is universal), returning (relativeUrl, size). */
    internal fun chooseArchive(pkg: Element, host: String = hostOs()): Pair<String?, Long> {
        val archives = firstChild(pkg, "archives") ?: return null to 0L
        val list = childElements(archives, "archive")
        // Prefer an exact host-os match; fall back to a universal (no host-os) archive.
        val chosen = list.firstOrNull { childText(it, "host-os") == host } ?: list.firstOrNull { childText(it, "host-os") == null }
        ?: return null to 0L
        val complete = firstChild(chosen, "complete") ?: return null to 0L
        val url = childText(complete, "url")
        val size = childText(complete, "size")?.toLongOrNull() ?: 0L
        return url to size
    }

    /** "macosx" / "windows" / "linux" — the values the SDK repo uses in `host-os`. */
    fun hostOs(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "macosx"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    }

    private fun childText(parent: Element, localName: String): String? =
        firstChild(parent, localName)?.textContent?.trim()?.ifEmpty { null }

    private fun firstChild(parent: Element, localName: String): Element? = childElements(parent, localName).firstOrNull()

    private fun childElements(parent: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && n.localName == localName) out += n
        }
        return out
    }

    // ---- extraction ----

    private fun extractZip(zip: Path, destDir: Path) {
        Files.createDirectories(destDir)
        ZipInputStream(BufferedInputStream(Files.newInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = destDir.resolve(entry.name).normalize()
                require(target.startsWith(destDir)) { "Zip entry escapes target: ${entry.name}" } // zip-slip guard
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Place the extracted [staging] content at [installDir], flattening the archive's single root dir
     *  (repo zips wrap their payload in one folder, like `android-14/` or `cmdline-tools/`). */
    /** The adb executable inside a `platform-tools` dir, or null when it is not there. */
    fun adbIn(platformTools: Path): Path? =
        sequenceOf("adb", "adb.exe").map { platformTools.resolve(it) }.firstOrNull { Files.isRegularFile(it) }

    /**
     * Give the tools back their executable bit. A zip read through [ZipInputStream] carries no Unix modes, so
     * an extracted `adb` cannot be run. Every top-level file without an extension is a tool (`adb`, `fastboot`,
     * `sqlite3`…); the libraries and data files beside them keep their modes. A no-op where the file system
     * has no POSIX permissions (Windows runs `adb.exe` by its extension).
     */
    private fun markToolsExecutable(dir: Path) {
        if (!Files.getFileStore(dir).supportsFileAttributeView("posix")) return
        Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it) && '.' !in it.fileName.toString() }.forEach { tool ->
                runCatching {
                    val perms = Files.getPosixFilePermissions(tool).toMutableSet()
                    perms += java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                    perms += java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
                    perms += java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
                    Files.setPosixFilePermissions(tool, perms)
                }
            }
        }
    }

    private fun placeInto(staging: Path, installDir: Path) {
        val roots = Files.list(staging).use { it.collect(Collectors.toList()) }
        val src = if (roots.size == 1 && Files.isDirectory(roots[0])) roots[0] else staging
        if (Files.exists(installDir)) deleteRecursively(installDir)
        Files.createDirectories(installDir.parent)
        runCatching { Files.move(src, installDir, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { copyRecursively(src, installDir) } // cross-device move → copy
    }

    private fun copyRecursively(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { p ->
                val rel = from.relativize(p)
                val dest = to.resolve(rel.toString())
                if (Files.isDirectory(p)) Files.createDirectories(dest)
                else { Files.createDirectories(dest.parent); Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }
}

/** The network seam used by [AndroidSdkInstaller] (and JDK download), so the logic is testable offline. */
interface SdkNetFetcher {
    fun fetchText(url: String): String?
    fun download(url: String, dest: Path, onProgress: (read: Long, total: Long) -> Unit): Boolean

    /** Bytes [start]..[endInclusive] of [url] as a stream, or null when the server does not honor Range. */
    fun openRange(url: String, start: Long, endInclusive: Long): InputStream? = null

    /** Bytes [start]..[endInclusive] of [url], or null when the server does not honor Range. */
    fun readRange(url: String, start: Long, endInclusive: Long): ByteArray? =
        openRange(url, start, endInclusive)?.use { it.readBytes() }
}

/** Default [SdkNetFetcher] over `HttpURLConnection` (works on desktop and ART). */
object HttpSdkNetFetcher : SdkNetFetcher {
    override fun fetchText(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        conn.inputStream.use { it.readBytes().decodeToString() }
    }.getOrNull()

    override fun openRange(url: String, start: Long, endInclusive: Long): InputStream? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=$start-$endInclusive")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_PARTIAL) { conn.disconnect(); null } else conn.inputStream
        } catch (e: IOException) {
            conn.disconnect(); null
        }
    }

    override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean = runCatching {
        // Resume a partial download: ask for the remaining bytes (HTTP Range) and append. If the server
        // ignores the range (200 OK) or the partial already covers the file (416), fall back cleanly.
        val existing = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        if (code == 416) return@runCatching true // requested range past EOF ⇒ already fully downloaded
        val resume = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
        val total = if (resume) existing + conn.contentLengthLong else conn.contentLengthLong
        val opts = if (resume) arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                   else arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        conn.inputStream.use { input ->
            Files.newOutputStream(dest, *opts).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = if (resume) existing else 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }
        true
    }.getOrDefault(false)
}
