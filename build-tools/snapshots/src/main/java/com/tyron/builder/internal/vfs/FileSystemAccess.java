package com.tyron.builder.internal.vfs;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.SnapshottingFilter;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/* Provides access to snapshots of the content and metadata of the file system.
 *
 * The implementation will attempt to efficiently honour the queries, maintaining some or all
 * state in-memory and dealing with concurrent access to the same parts of the file system.
 *
 * The file system access needs to be informed when some state on disk changes, so it does not
 * become out of sync with the actual file system.
 */
public interface FileSystemAccess {

    /**
     * Visits the hash of the content of the file only if the file is a regular file.
     *
     * @return the visitor function applied to the found snapshot.
     */
    <T> Optional<T> readRegularFileContentHash(String location, Function<HashCode, T> visitor);

    /**
     * Visits the hierarchy of files at the given location.
     */
    <T> T read(String location, Function<FileSystemLocationSnapshot, T> visitor);

    /**
     * Visits the hierarchy of files which match the filter at the given location.
     * <p>
     * The consumer is only called if if something matches the filter.
     */
    void read(String location,
              SnapshottingFilter filter,
              Consumer<FileSystemLocationSnapshot> visitor);

    /**
     * Runs an action which potentially writes to the given locations.
     */
    void write(Iterable<String> locations, Runnable action);

    /**
     * Updates the cached state at the location with the snapshot.
     */
    void record(FileSystemLocationSnapshot snapshot);

    interface WriteListener {
        void locationsWritten(Iterable<String> locations);
    }
}