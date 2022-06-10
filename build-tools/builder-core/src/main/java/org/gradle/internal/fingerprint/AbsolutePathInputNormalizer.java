package org.gradle.internal.fingerprint;

import org.gradle.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} that uses absolute paths for input files. The default.
 */
public interface AbsolutePathInputNormalizer extends FileNormalizer {
}
