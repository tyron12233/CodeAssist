package dev.ide.core

import dev.ide.core.plugins.BUILT_IN_INDEX_EXTENSIONS
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.compile.BundledKotlinStdlib
import dev.ide.platform.log.Log
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Index segments for the jars the app itself ships (android.jar, the desugar stubs, kotlin-stdlib), built at
 * APK build time and unpacked into the shared index cache on device. Without them every install, and every
 * update that changes an index format, indexed android.jar on the phone first: about 90% of all index entries
 * and several seconds before completion works.
 *
 * Correct by construction rather than by trust: [generate] runs the real [IndexServiceImpl] with the IDE's own
 * [BUILT_IN_INDEX_EXTENSIONS], and the segments land at the paths the device computes from the same stable jar
 * ids ([stableId]). A segment whose extension version or jar differs is simply never looked up, and the device
 * builds that one itself.
 */
object PrebuiltIndexSegments {
    /** The APK asset holding the zipped segments. */
    const val ASSET = "index-prebuilt.zip"

    private val log by lazy { Log.logger("index.prebuilt") }

    /**
     * The path-free cache id of a bundled jar: its file name and size. Shared with the scope the IDE builds, so
     * a segment generated on the build host and one looked up on device agree on the key.
     */
    fun stableId(jar: Path): String = "${jar.fileName}-${runCatching { Files.size(jar) }.getOrDefault(0L)}"

    /** Index [jars] with the IDE's own extensions and zip the resulting segment store into [out]. */
    fun generate(jars: List<Path>, out: Path) {
        val work = Files.createTempDirectory("prebuilt-index")
        try {
            val svc = IndexServiceImpl(BUILT_IN_INDEX_EXTENSIONS, work)
            try {
                runBlocking {
                    svc.ensureUpToDate(IndexScope(libraryJars = jars, stableJarIds = jars.associateWith { stableId(it) }))
                }
                check(svc.status.ready) { "prebuilt index build incomplete: ${svc.status.message}" }
            } finally {
                svc.close()
            }
            Files.createDirectories(out.toAbsolutePath().parent)
            ZipOutputStream(Files.newOutputStream(out)).use { zip ->
                val files = Files.walk(work).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".seg") }.collect(java.util.stream.Collectors.toList())
                }
                // Sorted, with a fixed timestamp, so the same inputs give the same asset bytes.
                for (f in files.sortedBy { work.relativize(it).toString() }) {
                    val entry = ZipEntry(work.relativize(f).toString().replace('\\', '/')).apply { time = 0L }
                    zip.putNextEntry(entry)
                    Files.copy(f, zip)
                    zip.closeEntry()
                }
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Unpack [zip] into [cacheRoot], skipping every segment already there (identical by construction: same
     * content key). Each file is written to a temp and moved into place, so a concurrent index build never sees
     * a partial segment. Returns how many segments were written.
     */
    fun install(zip: InputStream, cacheRoot: Path): Int {
        val root = cacheRoot.toAbsolutePath().normalize()
        var written = 0
        ZipInputStream(zip).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.isDirectory) continue
                val target = root.resolve(entry.name).normalize()
                require(target.startsWith(root)) { "prebuilt index entry escapes the cache: ${entry.name}" }
                if (Files.exists(target)) continue
                Files.createDirectories(target.parent)
                val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
                try {
                    Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                    runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
                        .getOrElse { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
                    written++
                } finally {
                    Files.deleteIfExists(tmp)
                }
            }
        }
        return written
    }

    /** Where a host's prebuilt segments come from, and a stamp that changes when they might (the app version). */
    class Source(val stamp: String, val open: () -> InputStream)

    /** Set by a host that ships prebuilt segments (the Android app). Null everywhere else: nothing to install. */
    @Volatile
    var source: Source? = null

    /**
     * Unpack the host's prebuilt segments into [cacheRoot] once per app version. Called off the main thread just
     * before a project's first index pass, so the pass finds them as ordinary cache hits. Never throws: without
     * them the device simply builds the segments itself, as it always did.
     */
    @Synchronized
    fun ensureInstalled(cacheRoot: Path) {
        val src = source ?: return
        val marker = cacheRoot.resolve("prebuilt.provisioned")
        runCatching {
            // readAllBytes/write, not readString/writeString: those are Java 11 APIs Android lacks below API 33.
            if (Files.isRegularFile(marker) && String(Files.readAllBytes(marker), Charsets.UTF_8) == src.stamp) return
            val t0 = System.nanoTime()
            val written = src.open().use { install(it, cacheRoot) }
            Files.write(marker, src.stamp.toByteArray(Charsets.UTF_8))
            log.info("installed $written prebuilt index segment(s) in ${(System.nanoTime() - t0) / 1_000_000}ms")
        }.onFailure { log.warn("prebuilt index segments not installed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * Build-time entry point: `<out.zip> <jar>...`. The bundled kotlin-stdlib is added from the classpath
     * resource the app ships, so its bytes, and therefore its key, match the copy the device extracts.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "usage: <out.zip> <jar>..." }
        val out = Paths.get(args[0])
        val stdlib = if (BundledKotlinStdlib.isBundled()) BundledKotlinStdlib.cached() else null
        val jars = args.drop(1).map { Paths.get(it) } + listOfNotNull(stdlib)
        generate(jars, out)
        println("prebuilt index: ${jars.size} jar(s) -> $out (${Files.size(out)} bytes)")
    }
}
