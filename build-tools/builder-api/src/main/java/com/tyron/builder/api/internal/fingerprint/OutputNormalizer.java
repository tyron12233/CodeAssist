package com.tyron.builder.api.internal.fingerprint;

import com.tyron.builder.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} used for output files.
 *
 * Like {@link AbsolutePathInputNormalizer}, but ignoring missing files.
 */
public interface OutputNormalizer extends FileNormalizer {
}