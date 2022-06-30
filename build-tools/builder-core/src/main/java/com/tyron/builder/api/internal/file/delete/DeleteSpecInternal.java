package com.tyron.builder.api.internal.file.delete;

import com.tyron.builder.api.file.DeleteSpec;

/**
 * Internal Representation of a {@link DeleteSpec}
 */
public interface DeleteSpecInternal extends DeleteSpec {

    /**
     * @return the paths to be deleted.
     */
    Object[] getPaths();

    /**
     * Returns whether or not deletion will follow symlinks.
     *
     * @return whether or not deletion follows symlinks.
     */
    boolean isFollowSymlinks();
}
