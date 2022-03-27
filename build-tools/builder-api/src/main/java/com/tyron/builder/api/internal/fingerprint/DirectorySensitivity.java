package com.tyron.builder.api.internal.fingerprint;

import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;

import java.util.function.Predicate;

/**
 * Specifies how a fingerprinter should handle directories that are found in a filecollection.
 */
public enum DirectorySensitivity {
    /**
     * Whatever the default behavior is for the given fingerprinter.  For some fingerprinters, the
     * default behavior is to fingerprint directories, for others, they ignore directories by default.
     */
    DEFAULT(snapshot -> true),
    /**
     * Ignore directories
     */
    IGNORE_DIRECTORIES(snapshot -> snapshot.getType() != FileType.Directory),
    /**
     * Used to denote that no directory sensitivity has been specified explicitly.
     *
     * We currently use this to switch to {@link #IGNORE_DIRECTORIES} when an input is a file tree.
     *
     * @deprecated This should go away in 8.0, since the special case should go away.
     */
    @Deprecated
    UNSPECIFIED(snapshot -> {
        throw new AssertionError("Unspecified must not be used as directory sensitivity");
    });

    private final Predicate<FileSystemLocationSnapshot> fingerprintCheck;

    DirectorySensitivity(Predicate<FileSystemLocationSnapshot> fingerprintCheck) {
        this.fingerprintCheck = fingerprintCheck;
    }

    public boolean shouldFingerprint(FileSystemLocationSnapshot snapshot) {
        return fingerprintCheck.test(snapshot);
    }
}