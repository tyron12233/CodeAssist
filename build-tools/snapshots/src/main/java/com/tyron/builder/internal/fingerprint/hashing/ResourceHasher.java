package com.tyron.builder.internal.fingerprint.hashing;

/**
 * Hashes resources (e.g., a class file in a jar or a class file in a directory)
 */
public interface ResourceHasher extends ConfigurableNormalizer, RegularFileSnapshotContextHasher, ZipEntryContextHasher {
}