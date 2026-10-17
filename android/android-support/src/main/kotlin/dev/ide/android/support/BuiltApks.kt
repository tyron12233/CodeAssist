package dev.ide.android.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The APKs this IDE's own build signed, so the phone only ever installs those.
 *
 * Google Play rejects an app that installs APKs it downloaded, and its scorer flagged CodeAssist for a path
 * that did exactly that: a git clone (or an imported file) can bring a committed APK under `build/outputs/apk/`,
 * and the file tree offered to install anything at that path. Where an APK sits says nothing about where it
 * came from, so the install points ask this instead: is this the file the build wrote, byte for byte?
 *
 * One small file per APK path under the ledger directory, holding the size and SHA-256 the build recorded.
 * The directory must be app-private (not the projects folder, which clones, imports and the FTP inbox write
 * into), and shared by the processes that build and install: the IDE and its `:build` daemon. Each entry is
 * written whole and moved into place, so two processes never interleave one.
 *
 * Unconfigured ([init] never called, as on the desktop, which installs over adb), nothing is recorded and
 * [isRecorded] is false; the Android install points are the only callers that require it.
 */
object BuiltApks {
    @Volatile
    private var ledgerDir: Path? = null

    /** Keep the ledger in [dir] (null switches it off). Called once per process that builds or installs. */
    fun init(dir: Path?) {
        ledgerDir = dir
    }

    /** Whether a ledger is configured in this process. */
    val enabled: Boolean get() = ledgerDir != null

    /** Note [apk] as just written by the build. Best effort: a failure only means a later install is refused. */
    fun record(apk: Path) {
        val dir = ledgerDir ?: return
        runCatching {
            val path = canonical(apk)
            Files.createDirectories(dir)
            val entry = dir.resolve(keyOf(path))
            val tmp = Files.createTempFile(dir, "apk", ".tmp")
            Files.write(tmp, "$FORMAT ${Files.size(apk)} ${mtime(apk)} ${sha256(apk)}\n$path\n".toByteArray())
            Files.move(tmp, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * True when [apk] is the file the build recorded at that path: same size and SHA-256. This is the check an
     * install point makes. It hashes the whole APK, so call it off the main thread.
     */
    fun isRecorded(apk: Path): Boolean = runCatching {
        entryFor(apk)?.let { it.size == Files.size(apk) && it.sha256 == sha256(apk) } ?: false
    }.getOrDefault(false)

    /**
     * The cheap form of [isRecorded] for the build's own up-to-date check: an entry exists for [apk] with the
     * size and modification time the build recorded. No hashing, so it can run on every build.
     */
    fun looksRecorded(apk: Path): Boolean = runCatching {
        entryFor(apk)?.let { it.size == Files.size(apk) && it.mtime == mtime(apk) } ?: false
    }.getOrDefault(false)

    private class Entry(val size: Long, val mtime: Long, val sha256: String)

    private fun entryFor(apk: Path): Entry? {
        val dir = ledgerDir ?: return null
        return runCatching {
            if (!Files.isRegularFile(apk)) return null
            val path = canonical(apk)
            val file = dir.resolve(keyOf(path))
            if (!Files.isRegularFile(file)) return null
            val lines = Files.readAllLines(file)
            val f = lines.getOrNull(0)?.split(' ') ?: return null
            if (f.size != 4 || f[0] != FORMAT || lines.getOrNull(1) != path) return null
            Entry(f[1].toLongOrNull() ?: return null, f[2].toLongOrNull() ?: return null, f[3])
        }.getOrNull()
    }

    private fun mtime(file: Path): Long = Files.getLastModifiedTime(file).toMillis()

    private const val FORMAT = "v1"

    /** One spelling per file, so `/sdcard/...` and `/storage/emulated/0/...` name the same entry. */
    private fun canonical(apk: Path): String =
        runCatching { apk.toRealPath() }.getOrElse { apk.toAbsolutePath().normalize() }.toString()

    private fun keyOf(path: String): String = hex(MessageDigest.getInstance("SHA-256").digest(path.toByteArray()))

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
