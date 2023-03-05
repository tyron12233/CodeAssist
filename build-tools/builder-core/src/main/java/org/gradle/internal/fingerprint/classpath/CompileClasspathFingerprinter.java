package org.gradle.internal.fingerprint.classpath;

import org.gradle.api.file.FileCollection;
import org.gradle.internal.execution.fingerprint.FileCollectionFingerprinter;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;

/**
 * Responsible for calculating a {@link FileCollectionFingerprint} for a {@link FileCollection} representing a Java
 * compile classpath. Compared to {@link RelativePathFileCollectionFingerprinter} this fingerprinter orders files within any sub-tree.
 *
 * @see org.gradle.api.tasks.CompileClasspath
 */
public interface CompileClasspathFingerprinter extends FileCollectionFingerprinter {
}