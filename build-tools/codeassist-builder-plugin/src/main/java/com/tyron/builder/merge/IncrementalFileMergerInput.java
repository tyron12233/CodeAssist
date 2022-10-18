package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.files.RelativeFile;
import com.android.ide.common.resources.FileStatus;
import com.google.common.collect.ImmutableSet;
import java.io.InputStream;
import java.util.function.Predicate;

/**
 * Input for an incremental merge operation (see {@link IncrementalFileMerger#merge(java.util.List,
 * IncrementalFileMergerOutput, IncrementalFileMergerState, Predicate)}.
 *
 * <p>An input represents a relative tree and a set of changed paths. Note that deleted paths are
 * reported in the changes, although they are no longer part of the relative tree because, well,
 * they were deleted :)
 *
 * <p>The input contains <i>both</i> the updated relative files (see {@link #getUpdatedPaths()}) and
 * the current set of relative files (see {@link #getAllPaths()}.
 *
 * <p>Each {@link IncrementalFileMergerInput} is identified by a name and a contains a set of
 * updates on {@link RelativeFile}. These are usually obtained using the methods from {@link
 * com.tyron.builder.files.IncrementalRelativeFileSets}.
 *
 * <p>The input is responsible for reading input files given their paths to the input. This makes
 * the actual source of the data invisible to the user.
 *
 * <p>Because not all methods are necessarily needed for every merge operation, it is recommended to
 * use {@link LazyIncrementalFileMergerInput} as implementation.
 */
public interface IncrementalFileMergerInput extends OpenableCloseable {

    /**
     * Obtains all OS-independent paths of all files that were changed in this input.
     *
     * @return the paths, may be empty if no paths were changed
     */
    @NonNull
    ImmutableSet<String> getUpdatedPaths();

    /**
     * Obtains all OS-independent paths of all files that in this input, regardless of being changed
     * or not.
     *
     * @return the paths, may be empty if the relative tree of this input is empty
     */
    @NonNull
    ImmutableSet<String> getAllPaths();

    /**
     * Obtains the name of this input.
     *
     * @return the name
     */
    @NonNull
    String getName();

    /**
     * Obtains the status of a path in this input.
     *
     * @param path the OS-independent path; the path may or not exist in the input
     * @return the status of the path or {@code null} if the path does not exist in the input or
     * if the path has not been changed;
     * {@code null} is returned if and only if {@code !getUpdatedPaths().contains(path)}
     */
    @Nullable
    FileStatus getFileStatus(@NonNull String path);

    /**
     * Opens a path for reading. This method should only be called when the input is open.
     *
     * @param path the path
     * @return the input stream that should be closed by the caller before {@link #close()} is
     * called
     */
    @NonNull
    InputStream openPath(@NonNull String path);
}
