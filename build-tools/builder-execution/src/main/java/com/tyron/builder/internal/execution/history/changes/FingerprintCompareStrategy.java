package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;

/**
 * Strategy to compare two {@link FileCollectionFingerprint}s.
 *
 * The strategy first tries to do a trivial comparison and delegates the more complex cases to a separate implementation.
 */
public interface FingerprintCompareStrategy {
    /**
     * Visits the changes to file contents since the given fingerprint, subject to the given filters.
     *
     * @return Whether the {@link ChangeVisitor} is looking for further changes. See {@link ChangeVisitor#visitChange(Change)}.
     */
    boolean visitChangesSince(FileCollectionFingerprint previous, FileCollectionFingerprint current, String propertyTitle, ChangeVisitor visitor);
}