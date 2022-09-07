package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.files.RelativeFile;
import com.android.ide.common.resources.FileStatus;
import com.android.tools.build.apkzlib.utils.CachedSupplier;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of an {@link IncrementalFileMergerInput} that lazily loads required data.
 *
 * <p>In general, this is constructed not directly using the constructor (which absolutely can be
 * used), but using the factory methods in {@link LazyIncrementalFileMergerInputs}.
 */
public class LazyIncrementalFileMergerInput implements IncrementalFileMergerInput {

    /**
     * Name of the input.
     */
    @NonNull
    private final String name;

    /** Inputs and how they changed for the merge. */
    @VisibleForTesting @NonNull final CachedSupplier<Map<RelativeFile, FileStatus>> updates;

    /**
     * Map between OS-independent paths and the relative files they come from. This applies to
     * files in {@link #updates}.
     */
    @VisibleForTesting
    @NonNull
    final CachedSupplier<ImmutableMap<String, RelativeFile>> updatePaths;

    /**
     * Map between OS-independent paths and the relative files they come from. This applies to
     * all files that currently exist in the directory, regardless of being updated or not.
     */
    @VisibleForTesting
    @NonNull
    final CachedSupplier<ImmutableMap<String, RelativeFile>> filePaths;

    /**
     * All zip files that must be open so that entries may be read using {@link #openPath(String)}.
     */
    @VisibleForTesting
    @NonNull
    final CachedSupplier<ImmutableSet<File>> zips;

    /**
     * After {@link #open()} is invoked and before {@link #close()} is invoked, all zips in
     * {@link #zips} are open and the reference to the {@link ZFile} is stored here.
     *
     * <p>This will be {@code null} if {@link #open()} has not yet been called.
     */
    @Nullable
    Map<File, ZFile> openZips;

    /**
     * Creates a new input.
     *
     * @param name the input name
     * @param updates the file and how they were updated
     * @param files all files
     */
    public LazyIncrementalFileMergerInput(
            @NonNull String name,
            @NonNull CachedSupplier<Map<RelativeFile, FileStatus>> updates,
            @NonNull CachedSupplier<Set<RelativeFile>> files) {
        this.name = name;
        this.updates = updates;
        this.updatePaths =
                new CachedSupplier<>(
                        () -> {
                            ImmutableMap.Builder<String, RelativeFile> pathsBuilder =
                                    ImmutableMap.builder();
                            for (Map.Entry<RelativeFile, FileStatus> e : updates.get().entrySet()) {
                                pathsBuilder.put(e.getKey().getRelativePath(), e.getKey());
                            }

                            return pathsBuilder.build();
                        });

        this.filePaths =
                new CachedSupplier<>(
                        () -> {
                            ImmutableMap.Builder<String, RelativeFile> pathsBuilder =
                                    ImmutableMap.builder();
                            for (RelativeFile rf : files.get()) {
                                pathsBuilder.put(rf.getRelativePath(), rf);
                            }

                            return pathsBuilder.build();
                        });

        this.zips =
                new CachedSupplier<>(
                        () -> {
                            Set<File> zips = new HashSet<>();
                            // keep track of visitedZips to avoid calls to isFile()
                            Set<File> visitedZips = new HashSet<>();
                            for (RelativeFile rf : files.get()) {
                                if (rf.getType() == RelativeFile.Type.JAR) {
                                    if (visitedZips.contains(rf.getBase())) {
                                        continue;
                                    }
                                    visitedZips.add(rf.getBase());
                                    if (rf.getBase().isFile()) {
                                        zips.add(rf.getBase());
                                    }
                                }
                            }
                            return ImmutableSet.copyOf(zips);
                        });

        openZips = null;
    }

    @NonNull
    @Override
    public ImmutableSet<String> getUpdatedPaths() {
        return updatePaths.get().keySet();
    }

    @NonNull
    @Override
    public ImmutableSet<String> getAllPaths() {
        return filePaths.get().keySet();
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Nullable
    @Override
    public FileStatus getFileStatus(@NonNull String path) {
        RelativeFile rf = updatePaths.get().get(path);
        if (rf == null) {
            return null;
        }

        return updates.get().get(rf);
    }

    @NonNull
    @Override
    public InputStream openPath(@NonNull String path) {
        Preconditions.checkState(openZips != null, "input not open");

        RelativeFile rf = filePaths.get().get(path);
        Preconditions.checkState(rf != null, "Unknown file: %s", path);

        if (rf.getType() == RelativeFile.Type.JAR) {
            ZFile zf = openZips.get(rf.getBase());
            Preconditions.checkState(zf != null, "Unknown base: %s", rf.getBase().getName());

            StoredEntry entry = zf.get(path);
            Preconditions.checkState(
                    entry != null,
                    "Unknown path %s in zip file %s",
                    path,
                    zf.getFile().getAbsolutePath());

            try {
                return entry.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try {
                return new FileInputStream(rf.getFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
     }

    @Override
    public void open() {
        Preconditions.checkState(openZips == null, "input already open");

        Map<File, ZFile> open = new HashMap<>();

        /*
         * Try to open all zips; if any fails store the exception.
         */
        UncheckedIOException failure = null;
        for (File f : zips.get()) {
            try {
                ZFile zf = ZFile.openReadOnly(f);
                open.put(f, zf);
            } catch (IOException e) {
                failure = new UncheckedIOException(e);
                break;
            }
        }

        /*
         * If we had a failure, try to close all zips that were open and throw the exception.
         */
        if (failure != null) {
            for (ZFile zf : open.values()) {
                try {
                    zf.close();
                } catch (IOException e) {
                    failure.addSuppressed(e);
                }
            }

            throw failure;
        }

        openZips = open;

    }

    @Override
    public void close() {
        Preconditions.checkState(openZips != null, "input not open");

        try (Closer closer = Closer.create()) {
            for (ZFile zf : openZips.values()) {
                closer.register(zf);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            openZips = null;
        }
    }
}
