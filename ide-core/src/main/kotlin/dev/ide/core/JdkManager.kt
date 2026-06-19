package dev.ide.core

import dev.ide.android.support.tools.HttpSdkNetFetcher
import dev.ide.android.support.tools.SdkNetFetcher
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import java.nio.file.Paths

/**
 * JDK source support for the editor's parameter-name + javadoc resolution. The running JDK usually ships its
 * sources as `lib/src.zip`; when it doesn't, a JDK that does (Eclipse Temurin) can be downloaded and only its
 * `src.zip` kept, copied to `<platformDir>/jdk-src.zip` (the shared toolchain dir the host supplies, so the
 * sources are downloaded once and reused across every project), which the analyzer adds to its source path.
 * Sources are attached for documentation only; the build JDK is not repointed.
 *
 * Only `src.zip` is kept (it is architecture-independent), so the download works on-device too: we never run
 * the downloaded JVM, and extraction is pure Java (no external `tar`, which an app process cannot exec on ART).
 */
class JdkManager(private val platformDir: Path, private val fetcher: SdkNetFetcher = HttpSdkNetFetcher) {

    data class Info(val home: String, val version: String, val srcZip: String?)

    /** The src.zip that's actually usable for docs: the running JDK's, or a previously downloaded override. */
    fun effectiveSrcZip(): Path? = currentSrcZip() ?: overrideSrcZip()

    fun info(): Info = Info(
        home = System.getProperty("java.home").orEmpty(),
        version = System.getProperty("java.version").orEmpty(),
        srcZip = effectiveSrcZip()?.toString(),
    )

    private fun currentSrcZip(): Path? =
        runCatching { Paths.get(System.getProperty("java.home")).resolve("lib").resolve("src.zip") }
            .getOrNull()?.takeIf { Files.isRegularFile(it) }

    /** A previously-downloaded JDK `src.zip` kept under `.platform`, if any. */
    fun overrideSrcZip(): Path? = platformDir.resolve("jdk-src.zip").takeIf { Files.isRegularFile(it) }

    /**
     * Download a Temurin JDK [feature] (e.g. 21), extract it, and keep its `src.zip` as the override so the
     * editor gets JDK sources. Returns null on success or an error message. Desktop only.
     */
    fun downloadJdkSources(feature: Int, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? {
        val os = adoptiumOs() ?: return "Unsupported OS for JDK download."
        val arch = adoptiumArch()
        val ext = if (os == "windows") "zip" else "tar.gz"
        val url = "https://api.adoptium.net/v3/binary/latest/$feature/ga/$os/$arch/jdk/hotspot/normal/eclipse?project=jdk"
        val archive = Files.createTempFile("jdk-$feature", ".$ext")
        try {
            if (!fetcher.download(url, archive, onProgress)) return "JDK download failed."
            Files.createDirectories(platformDir)
            val dest = platformDir.resolve("jdk-src.zip")
            // Pull just lib/src.zip straight out of the archive (no whole-JDK unpack, no external `tar`).
            val found = if (ext == "zip") extractSrcZipFromZip(archive, dest) else extractSrcZipFromTarGz(archive, dest)
            return if (found) null else "Downloaded JDK has no src.zip."
        } catch (e: Exception) {
            return "JDK download failed: ${e.message}"
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    private fun adoptiumOs(): String? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> "mac"
            os.contains("win") -> "windows"
            os.contains("nux") || os.contains("nix") -> "linux"
            else -> null
        }
    }

    private fun adoptiumArch(): String {
        val a = System.getProperty("os.arch").orEmpty().lowercase()
        return if (a.contains("aarch64") || a.contains("arm64")) "aarch64" else "x64"
    }

    /** Copy the first `src.zip` entry out of a JDK `.zip` (the Windows asset). Returns false if absent. */
    private fun extractSrcZipFromZip(archive: Path, dest: Path): Boolean {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.substringAfterLast('/') == "src.zip") {
                    Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING)
                    return true
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
        return false
    }

    /**
     * Stream a JDK `.tar.gz` (the macOS/Linux/Android asset) in pure Java (gunzip + a minimal USTAR/GNU tar
     * reader) and write out the first `src.zip` entry, skipping the rest. No external `tar` process (an app
     * cannot exec one on ART), and no full-JDK unpack into temp.
     */
    private fun extractSrcZipFromTarGz(archive: Path, dest: Path): Boolean {
        GZIPInputStream(BufferedInputStream(Files.newInputStream(archive))).use { gz ->
            val header = ByteArray(512)
            var pendingName: String? = null // a GNU long-name entry sets the next entry's real name
            while (readBlock(gz, header)) {
                if (header.all { it == 0.toByte() }) break // end-of-archive marker
                val type = header[156]
                val size = tarSize(header)
                if (type == 'L'.code.toByte()) { // GNU long name: the data block holds the next entry's path
                    val nameBytes = ByteArray(size.toInt())
                    if (!readBlock(gz, nameBytes)) break
                    pendingName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(gz, padding(size))
                    continue
                }
                val name = pendingName ?: tarName(header)
                pendingName = null
                val isFile = type == 0.toByte() || type == '0'.code.toByte()
                if (isFile && name.endsWith("src.zip")) {
                    Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                        .use { out -> copyN(gz, out, size) }
                    return true
                }
                skipFully(gz, size + padding(size))
            }
        }
        return false
    }

    // ---- minimal tar helpers ----

    /** Read exactly [buf].size bytes; false on a short read (clean EOF or truncation). */
    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off == buf.size
    }

    /** The NUL-terminated `name` field (0..100), prefixed by the ustar `prefix` field (345..500) when set. */
    private fun tarName(h: ByteArray): String {
        val name = cstr(h, 0, 100)
        val prefix = cstr(h, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The octal `size` field (124..136). */
    private fun tarSize(h: ByteArray): Long {
        val s = cstr(h, 124, 12).trim()
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun cstr(h: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = off + len
        while (end < limit && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    /** Bytes of trailing padding after a [size]-byte body to the next 512-byte boundary. */
    private fun padding(size: Long): Long = (512 - (size % 512)) % 512

    private fun skipFully(input: InputStream, n: Long) {
        var rem = n
        val buf = ByteArray(8192)
        while (rem > 0) {
            val read = input.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (read < 0) break
            rem -= read
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, n: Long) {
        var rem = n
        val buf = ByteArray(64 * 1024)
        while (rem > 0) {
            val read = input.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (read < 0) break
            out.write(buf, 0, read)
            rem -= read
        }
    }
}
