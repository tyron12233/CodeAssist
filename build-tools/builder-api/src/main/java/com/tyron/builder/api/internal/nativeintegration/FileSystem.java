package com.tyron.builder.api.internal.nativeintegration;

import com.tyron.builder.api.internal.file.Chmod;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.Stat;

import java.io.File;

/**
 * A file system accessible to Gradle.
 */
public interface FileSystem extends Chmod, Stat {
    /**
     * Default Unix permissions for directories, {@code 755}.
     */
    @SuppressWarnings("OctalInteger")
    int DEFAULT_DIR_MODE = 0755;

    /**
     * Default Unix permissions for files, {@code 644}.
     */
    @SuppressWarnings("OctalInteger")
    int DEFAULT_FILE_MODE = 0644;

    /**
     * Tells whether the file system is case sensitive.
     *
     * @return <code>true</code> if the file system is case sensitive, <code>false</code> otherwise
     */
    boolean isCaseSensitive();

    /**
     * Tells if the file system can create symbolic links. If the answer cannot be determined accurately,
     * <code>false</code> is returned.
     *
     * @return <code>true</code> if the file system can create symbolic links, <code>false</code> otherwise
     */
    boolean canCreateSymbolicLink();

    /**
     * Creates a symbolic link to a target file.
     *
     * @param link the link to be created
     * @param target the file to link to
     * @exception FileException if the operation fails
     */
    void createSymbolicLink(File link, File target) throws FileException;

    /**
     * Tells if the file is a symlink
     *
     * @param suspect the file to check
     * @return true if symlink, false otherwise
     */
    boolean isSymlink(File suspect);
}