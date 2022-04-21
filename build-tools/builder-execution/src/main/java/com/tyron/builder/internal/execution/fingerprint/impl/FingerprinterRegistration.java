package com.tyron.builder.internal.execution.fingerprint.impl;

import com.tyron.builder.internal.execution.fingerprint.FileCollectionFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.FileNormalizationSpec;
import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;

import java.util.Objects;

public class FingerprinterRegistration {
    private final FileNormalizationSpec spec;
    private final FileCollectionFingerprinter fingerprinter;

    private FingerprinterRegistration(FileNormalizationSpec spec, FileCollectionFingerprinter fingerprinter) {
        this.spec = spec;
        this.fingerprinter = fingerprinter;
    }

    public FileNormalizationSpec getSpec() {
        return spec;
    }

    public FileCollectionFingerprinter getFingerprinter() {
        return fingerprinter;
    }

    public static FingerprinterRegistration registration(DirectorySensitivity directorySensitivity, LineEndingSensitivity lineEndingSensitivity, FileCollectionFingerprinter fingerprinter) {
        return new FingerprinterRegistration(
                DefaultFileNormalizationSpec
                        .from(fingerprinter.getRegisteredType(), directorySensitivity, lineEndingSensitivity),
                fingerprinter
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        FingerprinterRegistration that = (FingerprinterRegistration) o;
        return spec.equals(that.spec) && fingerprinter.equals(that.fingerprinter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spec);
    }
}