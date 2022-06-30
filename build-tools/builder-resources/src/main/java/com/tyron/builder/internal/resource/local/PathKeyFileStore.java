package com.tyron.builder.internal.resource.local;

import org.jetbrains.annotations.Nullable;

/**
 * File store that accepts the target path as the key for the entry.
 */
public interface PathKeyFileStore extends FileStore<String>, FileStoreSearcher<String> {
    @Nullable
    LocallyAvailableResource get(String... path);
}
