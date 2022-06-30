package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;

import java.io.File;

public interface FileHasher {
    /**
     * Returns the hash of the current content of the given file. The provided file must exist and be a file (rather than, say, a directory).
     */
    HashCode hash(File file);

    /**
     * Returns the hash of the current content of the given file, assuming the given file metadata. The provided file must exist and be a file (rather than, say, a directory).
     */
    HashCode hash(File file, long length, long lastModified);
}