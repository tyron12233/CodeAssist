package com.tyron.builder.execution;

public interface WorkValidationWarningReporter {
    /**
     * Reports any validation warnings at the end of the build.
     *
     * Resets the warning state for the next build.
     */
    void reportWorkValidationWarningsAtEndOfBuild();
}