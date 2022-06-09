package com.tyron.builder.api.tasks;

/**
 * A {@code VerificationTask} is a task which performs some verification of the artifacts produced by a build.
 */
public interface VerificationTask {
    /**
     * Specifies whether the build should break when the verifications performed by this task fail.
     *
     * @param ignoreFailures false to break the build on failure, true to ignore the failures. The default is false.
     */
    void setIgnoreFailures(boolean ignoreFailures);

    /**
     * Specifies whether the build should break when the verifications performed by this task fail.
     *
     * @return false, when the build should break on failure, true when the failures should be ignored.
     */
    @Input
    boolean getIgnoreFailures();
}
