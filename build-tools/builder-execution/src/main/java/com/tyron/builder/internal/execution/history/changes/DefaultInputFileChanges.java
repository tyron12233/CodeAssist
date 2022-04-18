package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;

import java.util.SortedMap;

public class DefaultInputFileChanges extends AbstractFingerprintChanges implements InputFileChanges {
    private static final String TITLE = "Input";

    public DefaultInputFileChanges(SortedMap<String, FileCollectionFingerprint> previous, SortedMap<String, CurrentFileCollectionFingerprint> current) {
        super(previous, current, TITLE);
    }

    @Override
    public boolean accept(String propertyName, ChangeVisitor visitor) {
        CurrentFileCollectionFingerprint currentFileCollectionFingerprint = current.get(propertyName);
        FileCollectionFingerprint previousFileCollectionFingerprint = previous.get(propertyName);
        FingerprintCompareStrategy compareStrategy = determineCompareStrategy(currentFileCollectionFingerprint);
        return compareStrategy.visitChangesSince(previousFileCollectionFingerprint, currentFileCollectionFingerprint, TITLE, visitor);
    }
}