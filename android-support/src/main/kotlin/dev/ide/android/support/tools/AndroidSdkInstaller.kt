package dev.ide.android.support.tools

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A self-contained Android SDK package downloader — a small, cross-platform stand-in for `sdkmanager` that
 * talks to Google's SDK repository directly (so it works on desktop and on-device, where no `sdkmanager`
 * exists). It fetches the repository manifest, lists the installable packages (platforms, build-tools,
 * sources, command-line tools), and installs one by downloading its archive and extracting it into the SDK
 * layout. Network and disk are the only side effects; all I/O goes through an injected [SdkNetFetcher] so the
 * parsing/selection/path logic is unit-testable offline.
 *
 * Note: build-tools archives carry per-OS native binaries (so they're host-specific); platforms and sources
 * are architecture-independent and install identically everywhere.
 */
object AndroidSdkInstaller {

    const val REPO_BASE = "https://dl.google.com/android/repository/"
    private const val REPO_XML = "repository2-3.xml"

    enum class Category { PLATFORM, BUILD_TOOLS, SOURCES, CMDLINE_TOOLS, OTHER }

    /** One installable package from the repository manifest. [path] is the sdkmanager-style id
     *  (`platforms;android-34`); [archiveUrl] is absolute (or null if no archive fits this host). */
    data class RepoPackage(
        val path: String,
        val displayName: String,
        val revision: String,
        val category: Category,
        val archiveUrl: String?,
        val sizeBytes: Long,
    ) {
        /** Where this package installs under an SDK root: `platforms;android-34` → `platforms/android-34`. */
        fun installDir(sdkRoot: Path): Path = path.split(';').fold(sdkRoot) { acc, seg -> acc.resolve(seg) }
    }

    /** Fetch + parse the repository manifest into the available packages. Empty on a network/parse failure. */
    fun fetchPackages(fetcher: SdkNetFetcher = HttpSdkNetFetcher): List<RepoPackage> {
        val xml = fetcher.fetchText(REPO_BASE + REPO_XML) ?: return emptyList()
        return runCatching { parsePackages(xml) }.getOrDefault(emptyList())
    }

    internal fun parsePackages(xml: String): List<RepoPackage> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(xml.byteInputStream())
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
            out += RepoPackage(path, displayName, revision, category, url?.let { REPO_BASE + it }, size)
        }
        return out
    }

    /** sdkmanager-style ids already installed under [sdkRoot] (so the UI can mark them). */
    fun installedPackages(sdkRoot: Path): Set<String> {
        val out = HashSet<String>()
        fun listDirs(rel: String, idPrefix: String) {
            val d = sdkRoot.resolve(rel)
            if (!Files.isDirectory(d)) return
            Files.list(d).use { s -> s.filter { Files.isDirectory(it) }.forEach { out += "$idPrefix${it.fileName}" } }
        }
        listDirs("platforms", "platforms;")
        listDirs("build-tools", "build-tools;")
        listDirs("sources", "sources;")
        listDirs("cmdline-tools", "cmdline-tools;")
        return out
    }

    /**
     * Install [pkg] into [sdkRoot]: download its archive, extract it, and place the contents at the package's
     * install dir. [onProgress] receives (bytesRead, totalBytes) during the download. Returns null on success,
     * or an error message.
     */
    fun install(pkg: RepoPackage, sdkRoot: Path, fetcher: SdkNetFetcher = HttpSdkNetFetcher, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val url = pkg.archiveUrl ?: return "No archive available for this host."
        val tmpZip = Files.createTempFile("sdkpkg", ".zip")
        try {
            if (!fetcher.download(url, tmpZip, onProgress)) return "Download failed."
            val staging = Files.createTempDirectory("sdkpkg-extract")
            try {
                extractZip(tmpZip, staging)
                placeInto(staging, pkg.installDir(sdkRoot))
            } finally {
                deleteRecursively(staging)
            }
            return null
        } catch (e: Exception) {
            return "Install failed: ${e.message}"
        } finally {
            runCatching { Files.deleteIfExists(tmpZip) }
        }
    }

    /** The latest `cmdline-tools;latest` package, used to bootstrap an SDK that has no sdkmanager. */
    fun cmdlineToolsPackage(packages: List<RepoPackage>): RepoPackage? =
        packages.firstOrNull { it.category == Category.CMDLINE_TOOLS && it.path.endsWith(";latest") }
            ?: packages.firstOrNull { it.category == Category.CMDLINE_TOOLS }

    // ---- XML helpers ----

    internal fun categoryOf(path: String): Category = when {
        path.startsWith("platforms;") -> Category.PLATFORM
        path.startsWith("build-tools;") -> Category.BUILD_TOOLS
        path.startsWith("sources;") -> Category.SOURCES
        path.startsWith("cmdline-tools;") -> Category.CMDLINE_TOOLS
        else -> Category.OTHER
    }

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
}

/** Default [SdkNetFetcher] over `HttpURLConnection` (works on desktop and ART). */
object HttpSdkNetFetcher : SdkNetFetcher {
    override fun fetchText(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        conn.inputStream.use { it.readBytes().decodeToString() }
    }.getOrNull()

    override fun download(url: String, dest: Path, onProgress: (Long, Long) -> Unit): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            Files.newOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
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
