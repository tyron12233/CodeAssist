package com.tyron.builder.internal.fingerprint.classpath;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;

/**
 * Responsible for calculating a {@link FileCollectionFingerprint} for a {@link FileCollection} representing a Java
 * compile classpath. Compared to {@link RelativePathFileCollectionFingerprinter} this fingerprinter orders files within any sub-tree.
 *
 * @see com.tyron.builder.api.tasks.CompileClasspath
 */
public interface CompileClasspathFingerprinter extends FileCollectionFingerprinter {
}