package com.tyron.builder.caching.internal.controller;

import com.tyron.builder.caching.BuildCacheKey;

import java.io.IOException;
import java.io.OutputStream;

public interface BuildCacheStoreCommand {

    BuildCacheKey getKey();

    /**
     * Called at-most-once to initiate writing the artifact to the output stream.
     *
     * The output stream will be closed by this method.
     */
    Result store(OutputStream outputStream) throws IOException;

    interface Result {

        /**
         * The number of entries in the stored artifact.
         *
         * This is used as a rough metric of the complexity of the archive for processing
         * (in conjunction with the archive size).
         *
         * The meaning of “entry” is intentionally loose.
         */
        long getArtifactEntryCount();

    }

}
