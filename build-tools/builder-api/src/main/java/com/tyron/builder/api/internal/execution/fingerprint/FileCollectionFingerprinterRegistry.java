package com.tyron.builder.api.internal.execution.fingerprint;


public interface FileCollectionFingerprinterRegistry {
    FileCollectionFingerprinter getFingerprinter(FileNormalizationSpec spec);
}