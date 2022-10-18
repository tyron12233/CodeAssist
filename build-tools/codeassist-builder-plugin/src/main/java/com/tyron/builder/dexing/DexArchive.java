package com.tyron.builder.dexing;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface describing the DEX archive. It contains one DEX file per .class file that was processed
 * i.e. each DEX file in it, contains definition of exactly one class. Also, the directory structure
 * is preserved e.g. if a file com/example/tools/A.class was processed, it will have relative path
 * com/example/tools/A.dex in the archive.
 *
 * <p>Dex archives can be updated by adding new files.
 *
 * <p>When using instances of {@link DexArchive} make sure that you invoke {@link #close()} after
 * you are done using it.
 */
public interface DexArchive extends Closeable {

    /**
     * Returns the path to this DEX archive.
     *
     * @return path to this archive
     */
    @NotNull
    Path getRootPath();

    /**
     * Adds a DEX file to this dex archive from a byte[]. Adding is performed relative to the {@link
     * #getRootPath()}. In case file with the same relative path already exists, implementations of
     * this method should overwrite it with the new version of the file.
     *
     * @param relativePath file to be added to this archive
     * @param bytes the buffer to get the dex file content from
     * @param offset the offset for the buffer window.
     * @param end the end of the buffer window.
     * @throws IOException if the dex file cannot be written.
     */
    void addFile(@NotNull String relativePath, byte[] bytes, int offset, int end)
            throws IOException;

    /**
     * Returns all dex archive entries in this dex archive.
     *
     * <p>The dex archive entries are sorted to ensure deterministic order.
     */
    @NotNull
    List<DexArchiveEntry> getSortedDexArchiveEntries();
}