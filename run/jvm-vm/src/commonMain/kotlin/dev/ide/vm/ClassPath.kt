package dev.ide.vm

import dev.ide.kotlin.classfile.ZipArchive
import dev.ide.platform.FileSource
import dev.ide.platform.fileInfo
import dev.ide.platform.openFile
import dev.ide.platform.readFile

/** Where the VM finds class bytes: `internal/Name` to the `.class` file's contents, or null. */
fun interface ClassBytesSource {
    fun read(internalName: String): ByteArray?

    /** Every class-path resource at [path] (`META-INF/services/...`), one per entry that has it. */
    fun resources(path: String): List<ByteArray> = emptyList()
}

/**
 * Jars and class directories, searched in order. Each jar is opened once, on first lookup, and kept open:
 * a preview touches thousands of classes across a few dozen jars.
 */
class ClassPath(private val entries: List<String>) : ClassBytesSource {
    private var opened: List<Opened>? = null

    private class Opened(val directory: String?, val archive: ZipArchive?, val source: FileSource?)

    private fun open(): List<Opened> = opened ?: entries.mapNotNull { path ->
        val info = fileInfo(path) ?: return@mapNotNull null
        if (info.isDirectory) Opened(path.trimEnd('/'), null, null)
        else {
            val source = openFile(path) ?: return@mapNotNull null
            val archive = ZipArchive.open(source)
            if (archive == null) { source.close(); null } else Opened(null, archive, source)
        }
    }.also { opened = it }

    override fun read(internalName: String): ByteArray? {
        val entryName = "$internalName.class"
        for (e in open()) {
            if (e.directory != null) {
                readFile("${e.directory}/$entryName")?.let { return it }
            } else {
                val archive = e.archive ?: continue
                val entry = archive.entry(entryName) ?: continue
                return archive.read(entry)
            }
        }
        return null
    }

    override fun resources(path: String): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (e in open()) {
            if (e.directory != null) {
                readFile("${e.directory}/$path")?.let { out.add(it) }
            } else {
                val archive = e.archive ?: continue
                val entry = archive.entry(path) ?: continue
                archive.read(entry)?.let { out.add(it) }
            }
        }
        return out
    }

    fun close() {
        opened?.forEach { it.source?.close() }
        opened = null
    }
}
