package com.tyron.builder.internal.file;

import java.io.File;
import java.io.IOException;

/**
 * A file deleter that doesn't give up if deletion doesn't work on the first try.
 */
public interface Deleter {
    /**
     * Attempts to delete the given file or directory recursively.
     *
     * Can delete directories with contents.
     * Does not Follow symlinks.
     *
     * @return {@code true} if anything was removed, {@code false} if no change was
     *         attempted (because {@code target} didn't exist).
     *
     * @throws IOException when {@code target} cannot be deleted (with detailed error
     *         message).
     */
    boolean deleteRecursively(File target) throws IOException;

    /**
     * Attempts to delete the given file or directory recursively.
     *
     * Can delete directories with contents.
     * Follows symlinks pointing to directories when instructed to.
     *
     * @return {@code true} if anything was removed, {@code false} if no change was
     *         attempted (because {@code target} didn't exist).
     *
     * @throws IOException when {@code target} cannot be deleted (with detailed error
     *         message).
     */
    boolean deleteRecursively(File target, boolean followSymlinks) throws IOException;

    /**
     * Makes sure that the given target is an empty directory.
     *
     * If target is...
     *
     * <ul>
     *     <li>a directory, then its contents are removed recursively,</li>
     *     <li>a file or a symlink, then it is deleted and a directory is created in its place,</li>
     *     <li>non-existent, then a directory is created in its place.</li>
     * </ul>
     *
     * Does not follow symlinks.
     *
     * @return {@code true} if anything was removed, {@code false} if no change was
     *         attempted (because {@code target} didn't exist).
     *
     * @throws IOException when {@code target} cannot be deleted (with detailed error
     *         message).
     */
    boolean ensureEmptyDirectory(File target) throws IOException;

    /**
     * Makes sure that the given target is an empty directory.
     *
     * If target is...
     *
     * <ul>
     *     <li>a directory, then its contents are removed recursively,</li>
     *     <li>a symlink pointing to an existing directory, then either the linked directory's
     *     contents are removed recursively (if {@code followSymlinks} is {@code true}),
     *     or the link is removed and a new directory is created (if {@code followSymlinks}
     *     is {@code false}),</li>
     *     <li>a file, or a symlink to an existing file, it is deleted and a directory is created in its place,</li>
     *     <li>non-existent, then a directory is created in its place.</li>
     * </ul>
     *
     * Follows symlinks pointing to directories when instructed to.
     *
     * @return {@code true} if anything was removed, {@code false} if no change was
     *         attempted (because {@code target} didn't exist).
     *
     * @throws IOException when {@code target} cannot be deleted (with detailed error
     *         message).
     */
    boolean ensureEmptyDirectory(File target, boolean followSymlinks) throws IOException;

    /**
     * Deletes a single file or an empty directory.
     *
     * Does not follow symlinks.
     *
     * @return {@code true} if the target existed, {@code false} if it didn't exist.
     *
     * @throws IOException if the file cannot be deleted.
     */
    boolean delete(File target) throws IOException;
}