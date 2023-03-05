package org.gradle.internal.execution.fingerprint;


public interface FileCollectionFingerprinterRegistry {
    FileCollectionFingerprinter getFingerprinter(FileNormalizationSpec spec);
}