package com.tyron.builder.files;

import com.android.ide.common.resources.FileStatus;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for incremental relative file sets, immutable maps of relative files to status.
 */
public final class IncrementalRelativeFileSets {

    /**
     * Utility class: no visible constructor.
     */
    private IncrementalRelativeFileSets() {
    }

    /**
     * Reads a directory and adds all files in the directory in a new incremental relative set. The
     * status of each file is set to {@link FileStatus#NEW}. This method is used to construct an
     * initial set of files and is, therefore, an incremental update from zero.
     *
     * @param directory the directory, must be an existing directory
     * @return the file set
     * @deprecated Prefer the new Gradle InputChanges API.
     */
    @Deprecated
    @NotNull
    public static ImmutableMap<RelativeFile, FileStatus> fromDirectory(@NotNull File directory) {
        Preconditions.checkArgument(directory.isDirectory(), "!directory.isDirectory()");
        return ImmutableMap.copyOf(
                Maps.asMap(
                        RelativeFiles.fromDirectory(directory),
                        Functions.constant(FileStatus.NEW)));
    }

    /**
     * Reads a zip file and adds all files in the file in a new incremental relative set. The
     * status of each file is set to {@link FileStatus#NEW}. This method is used to construct an
     * initial set of files and is, therefore, an incremental update from zero.
     *
     * @param zip the zip file to read, must be a valid, existing zip file
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static ImmutableMap<RelativeFile, FileStatus> fromZip(@NotNull File zip)
            throws IOException {
        return fromZip(zip, FileStatus.NEW);
    }

    /**
     * Reads a zip file and adds all files in the file in a new incremental relative set. The
     * status of each file is set to {@code status}.
     *
     * @param zip the zip file to read, must be a valid, existing zip file
     * @param status the status to set the files to
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static ImmutableMap<RelativeFile, FileStatus> fromZip(
            @NotNull File zip,
            FileStatus status)
            throws IOException {
        Preconditions.checkArgument(zip.isFile(), "!zip.isFile(): %s", zip);

        return ImmutableMap.<RelativeFile, FileStatus>builder()
                .putAll(
                        Maps.asMap(
                                RelativeFiles.fromZip(new ZipCentralDirectory(zip)), f -> status))
                .build();
    }

    /**
     * Reads a zip file and adds all files in the file in a new incremental relative set. The status
     * of each file is set to {@link FileStatus#NEW}. This method is used to construct an initial
     * set of files and is, therefore, an incremental update from zero.
     *
     * @param zip the zip file to read, must be a valid, existing zip file
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static ImmutableMap<RelativeFile, FileStatus> fromZip(@NotNull ZipCentralDirectory zip)
            throws IOException {
        return fromZip(zip, FileStatus.NEW);
    }

    /**
     * Reads a zip file and adds all files in the file in a new incremental relative set. The status
     * of each file is set to {@code status}.
     *
     * @param zip the zip file to read, must be a valid, existing zip file
     * @param status the status to set the files to
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static ImmutableMap<RelativeFile, FileStatus> fromZip(
            @NotNull ZipCentralDirectory zip, FileStatus status) throws IOException {
        return ImmutableMap.<RelativeFile, FileStatus>builder()
                .putAll(Maps.asMap(RelativeFiles.fromZip(zip), f -> status))
                .build();
    }

    /**
     * Computes the incremental file set that results from comparing a zip file with a possibly
     * existing cached file. If the cached file does not exist, then the whole zip is reported as
     * {@link FileStatus#NEW}. If {@code zip} does not exist and a cached file exists, then the
     * whole zip is reported as {@link FileStatus#REMOVED}. Otherwise, both zips are compared and
     * the difference returned.
     *
     * @param zipCentralDirectory the zip file to read, must be a valid, existing zip file
     * @param cache the cache where to find the old version of the zip
     * @param cacheUpdates receives all runnables that will update the cache; running all runnables
     *     placed in this set will ensure that a second invocation of this method reports no changes
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static Map<RelativeFile, FileStatus> fromZip(
            @NotNull ZipCentralDirectory zipCentralDirectory,
            @NotNull KeyedFileCache cache,
            @NotNull Set<Runnable> cacheUpdates)
            throws IOException {
        File zipFile = zipCentralDirectory.getFile();
        File oldFile = cache.get(zipFile);
        if (oldFile == null) {
            /*
             * No old zip in cache. If the zip also doesn't exist, report all empty.
             */
            if (!zipFile.isFile()) {
                return ImmutableMap.of();
            }

            cacheUpdates.add(() -> {
                try {
                    cache.add(zipCentralDirectory);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return fromZip(zipCentralDirectory, FileStatus.NEW);
        }

        if (!zipFile.isFile()) {
            /*
             * Zip does not exist, but a cached version does. This means the zip was deleted
             * and all entries are removed.
             */
            ZipCentralDirectory oldCdr = new ZipCentralDirectory(oldFile);
            Collection<DirectoryEntry> entries = oldCdr.getEntries().values();

            Map<RelativeFile, FileStatus> map = Maps.newHashMapWithExpectedSize(entries.size());

            for (DirectoryEntry entry : entries) {
                map.put(new RelativeFile(zipFile, entry.getName()), FileStatus.REMOVED);
            }

            cacheUpdates.add((() -> {
                try {
                    cache.remove(zipFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
            return Collections.unmodifiableMap(map);
        }

        /*
         * We have both a new and old zip. Compare both.
         */
        Map<RelativeFile, FileStatus> result = Maps.newHashMap();

        Closer closer = Closer.create();
        try {
            Map<String, DirectoryEntry> newEntries = zipCentralDirectory.getEntries();
            Map<String, DirectoryEntry> oldEntries = new ZipCentralDirectory(oldFile).getEntries();

            /*
             * Search for new and modified files.
             */
            for (DirectoryEntry entry : newEntries.values()) {
                String path = entry.getName();
                RelativeFile newRelative = new RelativeFile(zipFile, path);

                DirectoryEntry oldEntry = oldEntries.get(path);
                if (oldEntry == null) {
                    result.put(newRelative, FileStatus.NEW);
                    continue;
                }

                if (oldEntry.getCrc32() != entry.getCrc32()
                        || oldEntry.getSize() != entry.getSize()) {
                    result.put(newRelative, FileStatus.CHANGED);
                }

                /*
                 * If we get here, then the file exists in both unmodified.
                 */
            }

            for (DirectoryEntry entry : oldEntries.values()) {
                String path = entry.getName();
                RelativeFile oldRelative = new RelativeFile(zipFile, path);

                DirectoryEntry newEntry = newEntries.get(path);
                if (newEntry == null) {
                    /*
                     * File does not exist in new. It has been deleted.
                     */
                    result.put(oldRelative, FileStatus.REMOVED);
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t, IOException.class);
        } finally {
            closer.close();
        }

        cacheUpdates.add(() -> {
            try {
                cache.add(zipCentralDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return Collections.unmodifiableMap(result);
    }

}