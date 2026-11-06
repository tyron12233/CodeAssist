package com.zipcli.ke.fs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NIO backend for app-scoped storage.
 * Mounts map virtual prefixes to real directories:
 *   "/home"        -> filesDir/zipcli
 *   "/sdcard-ext"  -> getExternalFilesDir(null)/zipcli
 * SAF mounts ("/tree/<id>") are added later by SafFileSystem.
 */
class NioFileSystem(private val mounts: Map<String, File>) : TerminalFileSystem {

    private fun resolveReal(virtual: String): File {
        val n = VirtualPath.normalize(virtual)
        val mount = mounts.entries
            .filter { (prefix, _) -> n == prefix || n.startsWith("$prefix/") }
            .maxByOrNull { it.key.length }
            ?: throw FsException("No mount for path: $n (try: ${mounts.keys.joinToString()})")
        val rel = n.removePrefix(mount.key).trimStart('/')
        return if (rel.isEmpty()) mount.value else File(mount.value, rel)
    }

    private fun entry(virtualPath: String, real: File): FileEntry {
        val vp = VirtualPath.normalize(virtualPath)
        return FileEntry(
            name = if (vp == "/") "/" else vp.substringAfterLast('/'),
            path = vp,
            isDirectory = real.isDirectory,
            size = if (real.isFile) real.length() else 0L,
            lastModified = real.lastModified(),
        )
    }

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (!real.exists()) throw FsException("Not found: $path")
        if (!real.isDirectory) throw FsException("Not a directory: $path")
        val vp = VirtualPath.normalize(path)
        real.listFiles()?.map { child ->
            val childVp = if (vp == "/") "/${child.name}" else "$vp/${child.name}"
            entry(childVp, child)
        } ?: emptyList()
    }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (!real.exists()) null else entry(path, real)
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolveReal(path).exists()
    }

    override suspend fun mkdirs(path: String): Unit = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (real.exists() && !real.isDirectory) throw FsException("File exists: $path")
        if (!real.mkdirs() && !real.isDirectory) throw FsException("Cannot create: $path")
    }

    override suspend fun createFile(path: String): Unit = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (real.exists()) throw FsException("Already exists: $path")
        real.parentFile?.mkdirs() ?: throw FsException("Invalid path: $path")
        if (!real.createNewFile()) throw FsException("Cannot create: $path")
    }

    override suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (!real.isFile) throw FsException("Not a file: $path")
        real.readText()
    }

    override suspend fun writeText(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        real.parentFile?.mkdirs() ?: throw FsException("Invalid path: $path")
        real.writeText(content)
    }

    override suspend fun copy(src: String, dst: String, recursive: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val s = resolveReal(src); val d = resolveReal(dst)
            if (!s.exists()) throw FsException("Not found: $src")
            if (s.isDirectory) {
                if (!recursive) throw FsException("$src is a directory; use -r")
                copyDir(s.toPath(), d.toPath())
            } else {
                d.parentFile?.mkdirs()
                Files.copy(s.toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

    private fun copyDir(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dst.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    target.parent?.let { Files.createDirectories(it) }
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    override suspend fun move(src: String, dst: String): Unit = withContext(Dispatchers.IO) {
        val s = resolveReal(src); val d = resolveReal(dst)
        if (!s.exists()) throw FsException("Not found: $src")
        if (d.exists()) throw FsException("Target exists: $dst")
        d.parentFile?.mkdirs()
        if (!s.renameTo(d)) {
            copy(src, dst, recursive = true)
            delete(src, recursive = true)
        }
    }

    override suspend fun delete(path: String, recursive: Boolean): Unit = withContext(Dispatchers.IO) {
        val real = resolveReal(path)
        if (!real.exists()) throw FsException("Not found: $path")
        if (real.isDirectory) {
            if (!recursive) throw FsException("$path is a directory; use -r")
            if (!real.deleteRecursively()) throw FsException("Failed to delete $path")
        } else if (!real.delete()) throw FsException("Failed to delete $path")
    }

    override suspend fun walk(root: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val real = resolveReal(root)
        if (!real.exists()) throw FsException("Not found: $root")
        val results = mutableListOf<FileEntry>()
        fun visit(file: File, vp: String) {
            results += entry(vp, file)
            // Do not follow symlinked directories: a link loop would never terminate.
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.listFiles()?.forEach { c ->
                visit(c, if (vp == "/") "/${c.name}" else "$vp/${c.name}")
            }
        }
        visit(real, VirtualPath.normalize(root))
        results
    }

    override suspend fun openRead(path: String): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(resolveReal(path))
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream =
        withContext(Dispatchers.IO) {
            val real = resolveReal(path)
            real.parentFile?.mkdirs()
            FileOutputStream(real, append)
        }
}