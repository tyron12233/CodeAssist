package org.gradle.internal.execution.fingerprint;

import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.api.tasks.FileNormalizer;

/**
 * Specifies criteria for selecting a {@link FileCollectionFingerprinter}.
 */
public interface FileNormalizationSpec {
    Class<? extends FileNormalizer> getNormalizer();

    DirectorySensitivity getDirectorySensitivity();

    LineEndingSensitivity getLineEndingNormalization();
}