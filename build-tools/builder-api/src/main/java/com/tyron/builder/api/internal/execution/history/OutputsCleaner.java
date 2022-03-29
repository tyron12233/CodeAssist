package com.tyron.builder.api.internal.execution.history;

import com.google.common.collect.Ordering;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshotUtil;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Cleans outputs, removing empty directories.
 *
 * This class should be used when cleaning output directories when only a subset of the files can be deleted.
 * After cleaning up a few output directories, the method {@link #cleanupDirectories()} cleans the directories which became empty.
 *
 * IMPORTANT: This class is stateful, so it can't be used as a service.
 */
public class OutputsCleaner {
    private static final Logger LOGGER = Logger.getLogger("OutputsCleaner");

    private final Deleter deleter;
    private final PriorityQueue<File> directoriesToDelete;
    private final Predicate<File> fileSafeToDelete;
    private final Predicate<File> dirSafeToDelete;

    private boolean didWork;

    interface Deleter {
        void delete(File file);
    }

    public OutputsCleaner(Deleter deleter, Predicate<File> fileSafeToDelete, Predicate<File> dirSafeToDelete) {
        this.deleter = deleter;
        this.fileSafeToDelete = fileSafeToDelete;
        this.dirSafeToDelete = dirSafeToDelete;
        this.directoriesToDelete = new PriorityQueue<>(10, Ordering.natural().reverse());
    }

    /**
     * Cleans up all locations {@link FileSystemSnapshot}, possible spanning multiple root directories.
     *
     * After cleaning up the files, the empty directories are removed as well.
     */
    public void cleanupOutputs(FileSystemSnapshot snapshot) throws IOException {
        // TODO We could make this faster by visiting the snapshot
        for (Map.Entry<String, FileSystemLocationSnapshot> entry : SnapshotUtil.index(snapshot).entrySet()) {
            cleanupOutput(new File(entry.getKey()), entry.getValue().getType());
        }
        cleanupDirectories();
    }

    /**
     * Cleans up a single location.
     *
     * Does not clean up directories, yet, though remembers them for deletion.
     * You should call {@link #cleanupDirectories()} after you are finished with the calls this method.
     */
    public void cleanupOutput(File file, FileType fileType) throws IOException {
        switch (fileType) {
            case Directory:
                markDirForDeletion(file);
                break;
            case RegularFile:
                if (fileSafeToDelete.test(file)) {
                    if (file.exists()) {
                        LOGGER.info("Deleting stale output file '" + file + " '.");
                        deleter.delete(file);
                        didWork = true;
                    }
                    markParentDirForDeletion(file);
                }
                break;
            case Missing:
                // Ignore missing files
                break;
            default:
                throw new AssertionError("Unknown file type: " + fileType);
        }
    }

    /**
     * Whether some actual deletion happened.
     */
    public boolean getDidWork() {
        return didWork;
    }

    private void markParentDirForDeletion(File f) {
        markDirForDeletion(f.getParentFile());
    }

    private void markDirForDeletion(@Nullable File dir) {
        if (dir != null && dirSafeToDelete.test(dir)) {
            directoriesToDelete.add(dir);
        }
    }

    /**
     * Cleans up empty directories marked for deletion in {@link #cleanupOutput(File, FileType)}.
     */
    public void cleanupDirectories() throws IOException {
        while (true) {
            File directory = directoriesToDelete.poll();
            if (directory == null) {
                break;
            }
            if (isEmpty(directory)) {
                LOGGER.info("Deleting stale empty output directory '" + directory + "'.");
                Files.delete(directory.toPath());
                didWork = true;
                markParentDirForDeletion(directory);
            }
        }
    }

    private boolean isEmpty(File parentDir) {
        String[] children = parentDir.list();
        return children != null && children.length == 0;
    }
}