package com.tyron.builder.internal.execution.fingerprint;


public interface FileCollectionFingerprinterRegistry {
    FileCollectionFingerprinter getFingerprinter(FileNormalizationSpec spec);
}