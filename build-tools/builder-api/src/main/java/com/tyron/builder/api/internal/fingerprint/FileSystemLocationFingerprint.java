package com.tyron.builder.api.internal.fingerprint;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.hash.Hashable;
import com.tyron.builder.api.internal.hash.Hashes;

import java.nio.charset.StandardCharsets;

/**
 * An immutable fingerprint of some aspects of a file's metadata and content.
 *
 * Should implement {@code #equals(Object)} and {@code #hashCode()} to compare these aspects.
 * Comparisons are very frequent, so these methods need to be fast.
 *
 * File fingerprints are cached between builds, so their memory footprint should be kept to a minimum.
 */
public interface FileSystemLocationFingerprint extends Comparable<FileSystemLocationFingerprint>, Hashable {
    HashCode DIR_SIGNATURE = Hashes.signature("DIR");
    HashCode MISSING_FILE_SIGNATURE = Hashes.signature("MISSING");

    String getNormalizedPath();
    HashCode getNormalizedContentHash();
    FileType getType();

}