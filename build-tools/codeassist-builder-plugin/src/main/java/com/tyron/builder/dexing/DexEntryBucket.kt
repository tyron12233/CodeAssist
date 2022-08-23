package com.tyron.builder.dexing

import com.google.common.io.Closer
import java.io.File
import java.io.Serializable
import java.util.zip.ZipFile

/**
 * A bucket of [DexEntry]'s.
 *
 * It is lightweight and [Serializable] so that it can be passed to workers (see `DexMergingTask`).
 */
class DexEntryBucket(
    private val dexEntries: List<DexEntry>
) : Serializable {

    @Suppress("UnstableApiUsage")
    fun getDexEntriesWithContents(): List<DexArchiveEntry> {
        val dexEntryWithContents = mutableListOf<DexArchiveEntry>()

        Closer.create().use { closer ->
            val openedJars = mutableMapOf<File, ZipFile>()
            dexEntries.forEach { dexEntry ->
                val contents = if (dexEntry.dexDirOrJar.isDirectory) {
                    dexEntry.dexDirOrJar.resolve(dexEntry.relativePath).readBytes()
                } else {
                    val openedJar = openedJars.computeIfAbsent(dexEntry.dexDirOrJar) {
                        ZipFile(dexEntry.dexDirOrJar).also {
                            closer.register(it)
                        }
                    }
                    openedJar.getInputStream(openedJar.getEntry(dexEntry.relativePath)).buffered()
                        .use { stream ->
                            stream.readBytes()
                        }
                }

                dexEntryWithContents.add(
                    DexArchiveEntry(
                        contents,
                        dexEntry.relativePath,
                        DexArchives.fromInput(dexEntry.dexDirOrJar.toPath())
                    )
                )
            }
        }

        return dexEntryWithContents.toList()
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A dex file and dex jar entry. It is identified by the directory or jar that contains it and its
 * relative path to the containing directory or jar.
 *
 * It is lightweight and [Serializable] so that it can be passed to workers (see `DexMergingTask`).
 */
class DexEntry(
    val dexDirOrJar: File,
    val relativePath: String
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }
}