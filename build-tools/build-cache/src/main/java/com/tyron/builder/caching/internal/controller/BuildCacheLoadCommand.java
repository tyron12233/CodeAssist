package com.tyron.builder.caching.internal.controller;

import com.tyron.builder.caching.BuildCacheKey;

import java.io.IOException;
import java.io.InputStream;

public interface BuildCacheLoadCommand<T> {

    BuildCacheKey getKey();

    /**
     * Called at-most-once to initiate loading the artifact from the input stream.
     *
     * The input stream will be closed by this method.
     */
    Result<T> load(InputStream inputStream) throws IOException;

    interface Result<T> {

        /**
         * The number of entries in the loaded artifact.
         *
         * This is used as a rough metric of the complexity of the archive for processing
         * (in conjunction with the archive size).
         *
         * The meaning of “entry” is intentionally loose.
         */
        long getArtifactEntryCount();

        /**
         * Any metadata about the loaded artifact.
         *
         * Value may not be null.
         */
        T getMetadata();
    }

}
