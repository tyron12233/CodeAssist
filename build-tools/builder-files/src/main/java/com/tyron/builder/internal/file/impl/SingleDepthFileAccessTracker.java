package com.tyron.builder.internal.file.impl;

import com.google.common.base.Preconditions;
import com.tyron.builder.internal.file.FileAccessTimeJournal;
import com.tyron.builder.internal.file.FileAccessTracker;

import java.io.File;
import java.nio.file.Path;

/**
 * Tracks access to files and directories at the supplied depth within the supplied base
 * directory by setting their last access time in the supplied {@link FileAccessTimeJournal}.
 */
@SuppressWarnings("Since15")
public class SingleDepthFileAccessTracker implements FileAccessTracker {

    private final Path baseDir;
    private final int endNameIndex;
    private final int startNameIndex;
    private final FileAccessTimeJournal journal;

    public SingleDepthFileAccessTracker(FileAccessTimeJournal journal, File baseDir, int depth) {
        this.journal = journal;
        Preconditions.checkArgument(depth > 0, "depth must be > 0: %s", depth);
        this.baseDir = baseDir.toPath().toAbsolutePath();
        this.startNameIndex = this.baseDir.getNameCount();
        this.endNameIndex = startNameIndex + depth;
    }

    @Override
    public void markAccessed(File file) {
        Path path = file.toPath().toAbsolutePath();
        if (path.getNameCount() >= endNameIndex && path.startsWith(baseDir)) {
            path = baseDir.resolve(path.subpath(startNameIndex, endNameIndex));
            journal.setLastAccessTime(path.toFile(), System.currentTimeMillis());
        }
    }
}
