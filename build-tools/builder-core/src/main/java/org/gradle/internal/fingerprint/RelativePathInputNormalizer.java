package org.gradle.internal.fingerprint;

import org.gradle.api.tasks.FileNormalizer;

/**
 * {@link FileNormalizer} that uses the location of files in a hierarchy as normalized paths.
 */
public interface RelativePathInputNormalizer extends FileNormalizer {
}