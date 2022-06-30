package com.tyron.builder.internal.execution.fingerprint;

import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.tasks.FileNormalizer;

/**
 * Specifies criteria for selecting a {@link FileCollectionFingerprinter}.
 */
public interface FileNormalizationSpec {
    Class<? extends FileNormalizer> getNormalizer();

    DirectorySensitivity getDirectorySensitivity();

    LineEndingSensitivity getLineEndingNormalization();
}