package org.gradle.internal.fingerprint;

import org.gradle.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} used for output files.
 *
 * Like {@link AbsolutePathInputNormalizer}, but ignoring missing files.
 */
public interface OutputNormalizer extends FileNormalizer {
}