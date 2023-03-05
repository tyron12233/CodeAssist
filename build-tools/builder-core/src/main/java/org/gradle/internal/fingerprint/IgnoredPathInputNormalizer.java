package org.gradle.internal.fingerprint;

import org.gradle.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} that ignores the path completely.
 */
public interface IgnoredPathInputNormalizer extends FileNormalizer {
}
