package com.tyron.builder.internal.execution.history.changes;


/**
 * Compares by absolute paths and file contents. Order does not matter.
 */
public class AbsolutePathFingerprintCompareStrategy extends AbstractFingerprintCompareStrategy {

    public static final FingerprintCompareStrategy INSTANCE = new AbsolutePathFingerprintCompareStrategy();

    private AbsolutePathFingerprintCompareStrategy() {
        super(new AbsolutePathChangeDetector<>(
                (previous, current) -> previous.getNormalizedContentHash().equals(current.getNormalizedContentHash()),
                FINGERPRINT_CHANGE_FACTORY
        ));
    }
}