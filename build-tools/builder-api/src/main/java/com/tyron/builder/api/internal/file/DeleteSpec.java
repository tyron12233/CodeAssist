package com.tyron.builder.api.internal.file;

/**
 * A specification for deleting files from the filesystem.
 */
public interface DeleteSpec {
    /**
     * Specifies the files to delete.
     *
     * @param files the list of files which should be deleted. Any type of object
     * accepted by {@link org.gradle.api.Project#files(Object...)}
     */
    DeleteSpec delete(Object... files);

    /**
     * Specifies whether or not symbolic links should be followed during deletion.
     *
     * @param followSymlinks deletion will follow symlinks when true.
     */
    void setFollowSymlinks(boolean followSymlinks);
}