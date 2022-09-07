package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.google.common.io.Closer;
import java.io.InputStream;
import java.util.List;

/**
 * Algorithm to merge streams. See {@link StreamMergeAlgorithms} for some commonly-used algorithms.
 */
public interface StreamMergeAlgorithm {

    /**
     * Merges the given streams.
     *
     * @param path the OS-independent path being merged
     * @param streams the source streams; must contain at least one element
     * @param closer the closer that will close the source streams and the merged stream (an
     *     implementation of this method will register the streams to be closed with this closer)
     * @return the merged stream
     */
    @NonNull
    InputStream merge(
            @NonNull String path, @NonNull List<InputStream> streams, @NonNull Closer closer);
}
