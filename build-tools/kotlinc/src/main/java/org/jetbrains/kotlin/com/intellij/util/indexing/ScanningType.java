package org.jetbrains.kotlin.com.intellij.util.indexing;

import java.util.Map;

public enum ScanningType {
    /**
     * Full project rescan forced by user via Repair IDE action
     */
    FULL_FORCED(true),

    /**
     * It's mandatory full project rescan on project open
     */
    FULL_ON_PROJECT_OPEN(true),

    /**
     * Full project rescan requested by some code
     */
    FULL(true),


    /**
     * Partial rescan forced by user via Repair IDE action on a limited scope (not full project)
     */
    PARTIAL_FORCED(false),

    /**
     * Partial project rescan requested by some code
     */
    PARTIAL(false),

    /**
     * Some files were considered changed and therefore rescanned
     */
    REFRESH(false);

    private final boolean isFull;

    ScanningType(boolean isFull) {

        this.isFull = isFull;
    }

    public boolean isFull() {
        return isFull;
    }

    public static class Companion {
        public static ScanningType merge(ScanningType first, ScanningType second) {
            return returnFirstRound(first, second, FULL_FORCED, FULL_ON_PROJECT_OPEN, FULL,
                    PARTIAL_FORCED, PARTIAL,
                    REFRESH);
        }

        private static ScanningType returnFirstRound(ScanningType first, ScanningType second, ScanningType... types) {
            for (ScanningType type : types) {
                if (first == type || second == type) {
                    return type;
                }
            }
            throw new IllegalStateException("Unexpected scanning type " + first + " " + second);
        }
    }
}
