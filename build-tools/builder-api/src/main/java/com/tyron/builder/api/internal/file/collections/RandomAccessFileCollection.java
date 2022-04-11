package com.tyron.builder.api.internal.file.collections;

import java.io.File;

/**
 * A file collection which can provide an efficient implementation to determine if it contains a given file.
 */
public interface RandomAccessFileCollection {
    boolean contains(File file);
}