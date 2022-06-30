package com.tyron.builder.internal.resource.local;

import com.tyron.builder.api.Action;

import java.io.File;

/**
 * An indexed store that maps a key to a file or directory.
 *
 * Most implementations do not provide locking, which must be coordinated by the caller.
 */
public interface FileStore<K> {
    /**
     * Moves the given file into the store.
     */
    LocallyAvailableResource move(K key, File source) throws FileStoreException;

    /**
     * Adds an entry to the store, using the given action to produce the file.
     *
     * @throws FileStoreAddActionException When the action fails
     * @throws FileStoreException On other failures
     */
    LocallyAvailableResource add(K key, Action<File> addAction) throws FileStoreException;
}
