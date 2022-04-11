package com.tyron.builder.api.internal.fingerprint;

import com.tyron.builder.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} that uses the location of files in a hierarchy as normalized paths.
 */
public interface RelativePathInputNormalizer extends FileNormalizer {
}