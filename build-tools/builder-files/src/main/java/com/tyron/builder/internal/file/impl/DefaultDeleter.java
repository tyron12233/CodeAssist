package com.tyron.builder.internal.file.impl;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.internal.file.Deleter;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

public class DefaultDeleter implements Deleter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDeleter.class);

    private final LongSupplier timeProvider;
    private final Predicate<? super File> isSymlink;
    private final boolean runGcOnFailedDelete;

    private static final int DELETE_RETRY_SLEEP_MILLIS = 10;

    @VisibleForTesting
    static final int MAX_REPORTED_PATHS = 16;

    @VisibleForTesting
    static final String HELP_FAILED_DELETE_CHILDREN = "Failed to delete some children. This might happen because a process has files open or has its working directory set in the target directory.";
    @VisibleForTesting
    static final String HELP_NEW_CHILDREN = "New files were found. This might happen because a process is still writing to the target directory.";

    public DefaultDeleter(LongSupplier timeProvider, Predicate<? super File> isSymlink, boolean runGcOnFailedDelete) {
        this.timeProvider = timeProvider;
        this.isSymlink = isSymlink;
        this.runGcOnFailedDelete = runGcOnFailedDelete;
    }

    @Override
    public boolean deleteRecursively(File target) throws IOException {
        return deleteRecursively(target, false);
    }

    @Override
    public boolean deleteRecursively(File target, boolean followSymlinks) throws IOException {
        if (target == null) {
            return false;
        }

        if (target.isFile()) {
            return target.delete();
        }

        FileUtils.deleteDirectory(target);
        return true;
    }

    @Override
    public boolean ensureEmptyDirectory(File target) throws IOException {
        return ensureEmptyDirectory(target, false);
    }

    @Override
    public boolean ensureEmptyDirectory(File target, boolean followSymlinks) throws IOException {
        if (!target.exists()) {
            return false;
        }
        FileUtils.cleanDirectory(target);
        return true;
    }

    @Override
    public boolean delete(File target) throws IOException {
        return target.delete();
    }
}
