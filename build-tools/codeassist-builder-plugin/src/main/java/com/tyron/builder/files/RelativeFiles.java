package com.tyron.builder.files;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.Sets;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Utilities to handle {@link RelativeFile}.
 */
public final class RelativeFiles {

    private RelativeFiles() {}

    /**
     * Loads all files in a directory recursively.
     *
     * @param directory the directory, must exist and be a readable directory
     * @return all files in the directory, sub-directories included
     */
    @NotNull
    public static Set<RelativeFile> fromDirectory(@NotNull File directory) {
        return Collections.unmodifiableSet(fromDirectory(directory, directory));
    }

    /**
     * Loads all files in a directory recursively, filtering the results with a predicate. Filtering
     * is only done at the end so, even if a directory is excluded from the filter, its files will
     * be included if they are accepted by the filter.
     *
     * @param directory the directory, must exist and be a readable directory
     * @param filter a predicate to filter which files should be included in the result; only files
     *     to whom the filter application results in {@code true} are included in the result
     * @return all files in the directory, sub-directories included
     */
    @NotNull
    public static Set<RelativeFile> fromDirectory(
            @NotNull File directory, @NotNull final Predicate<RelativeFile> filter) {
        return Collections.unmodifiableSet(
                Sets.filter(fromDirectory(directory, directory), filter::test));
    }

    /**
     * Loads all files in a directory recursively. Creates al files relative to another directory.
     *
     * @param base the directory to use for relative files
     * @param directory the directory to get files from, must exist and be a readable directory
     * @return all files in the directory, sub-directories included
     */
    @NotNull
    private static Set<RelativeFile> fromDirectory(@NotNull File base, @NotNull File directory) {
        Preconditions.checkArgument(base.isDirectory(), "!File.isDirectory(): %s", base);
        Preconditions.checkArgument(directory.isDirectory(), "!File.isDirectory(): %s", directory);

        Set<RelativeFile> files = Sets.newHashSet();
        File[] directoryFiles =
                Verify.verifyNotNull(directory.listFiles(), "directory.listFiles() == null");
        for (File file : directoryFiles) {
            if (file.isDirectory()) {
                files.addAll(fromDirectory(base, file));
            } else {
                files.add(new RelativeFile(base, file));
            }
        }

        return files;
    }

    /**
     * Constructs a predicate over relative files from a predicate over paths, applying it to the
     * normalized relative path contained in the relative file.
     *
     * @param predicate the file predicate
     * @return the relative file predicate built upon {@code predicate}
     */
    @NotNull
    public static Predicate<RelativeFile> fromPathPredicate(@NotNull Predicate<String> predicate) {
        return rf -> predicate.test(rf.getRelativePath());
    }

    /**
     * Reads a zip file and adds all files in the file in a new relative set.
     *
     * @param zip the zip file to read, must be a valid, existing zip file
     * @return the file set
     * @throws IOException failed to read the zip file
     */
    @NotNull
    public static Set<RelativeFile> fromZip(@NotNull ZipCentralDirectory zip) throws IOException {
        Collection<DirectoryEntry> values = zip.getEntries().values();
        Set<RelativeFile> files = Sets.newHashSetWithExpectedSize(values.size());

        for (DirectoryEntry entry : values) {
            files.add(new RelativeFile(zip.getFile(), entry.getName()));
        }

        return Collections.unmodifiableSet(files);
    }
}