package com.tyron.builder.cache;

import com.tyron.builder.api.Describable;

import java.io.File;
import java.util.Collection;

/**
 * Represents file-based store that can be cleaned by a {@link CleanupAction}.
 */
public interface CleanableStore extends Describable {

    /**
     * Returns the base directory that should be cleaned for this store.
     */
    File getBaseDir();

    /**
     * Returns the files used by this store for internal tracking
     * which should be exempt from the cleanup.
     */
    Collection<File> getReservedCacheFiles();

}