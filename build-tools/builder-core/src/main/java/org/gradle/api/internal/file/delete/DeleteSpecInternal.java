package org.gradle.api.internal.file.delete;

import org.gradle.api.file.DeleteSpec;

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
