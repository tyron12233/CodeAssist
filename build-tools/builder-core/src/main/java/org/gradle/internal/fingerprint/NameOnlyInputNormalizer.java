package org.gradle.internal.fingerprint;

import org.gradle.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} that uses the file name as normalized path.
 */
public interface NameOnlyInputNormalizer extends FileNormalizer {
}