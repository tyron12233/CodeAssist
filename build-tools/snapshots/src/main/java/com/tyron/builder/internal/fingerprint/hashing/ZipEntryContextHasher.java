package com.tyron.builder.internal.fingerprint.hashing;

import com.google.common.hash.HashCode;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Hashes a zip entry (e.g. a class file in a jar, a manifest file, a properties file)
 */
public interface ZipEntryContextHasher {
    /**
     * Returns {@code null} if the zip entry should be ignored.
     */
    @Nullable
    HashCode hash(ZipEntryContext zipEntryContext) throws IOException;
}
