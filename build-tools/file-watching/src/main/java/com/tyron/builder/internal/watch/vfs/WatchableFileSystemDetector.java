package com.tyron.builder.internal.watch.vfs;

import java.io.File;
import java.util.stream.Stream;

public interface WatchableFileSystemDetector {
    /**
     * Returns the mount points of all file systems for which file system watching is not supported.
     */
    Stream<File> detectUnsupportedFileSystems();
}
