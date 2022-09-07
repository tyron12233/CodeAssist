package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import java.io.InputStream;

/**
 * Writes the output of a merge. The output is provided on a path-by-path basis.
 *
 * <p>Writers need to be open before any operations can be performed and need to be closed to
 * ensure all changes have been persisted.
 *
 * <p>See {@link MergeOutputWriters} for some common implementations.
 */
public interface MergeOutputWriter extends OpenableCloseable {

    /**
     * Removes a path from the output.
     *
     * @param path the path to remove
     */
    void remove(@NonNull String path);

    /**
     * Creates a new path in the output.
     *
     * @param path the path to create
     * @param data the path's data
     * @param compress whether the data will be compressed
     */
    void create(@NonNull String path, @NonNull InputStream data, boolean compress);

    /**
     * Replaces a path's contents with new contents.
     *
     * @param path the path to replace
     * @param data the new path's data
     * @param compress whether the data will be compressed
     */
    void replace(@NonNull String path, @NonNull InputStream data, boolean compress);
}
