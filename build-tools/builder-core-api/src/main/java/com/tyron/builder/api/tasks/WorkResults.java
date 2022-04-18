package com.tyron.builder.api.tasks;

/**
 * Helps access trivial {@link WorkResult} objects.
 *
 * @since 4.2
 */
public class WorkResults {
    private static final WorkResult DID_WORK = () -> true;
    private static final WorkResult DID_NO_WORK = () -> false;

    private WorkResults() {}

    /**
     * Returns a {@link WorkResult} object representing work done according to the given parameter.
     */
    public static WorkResult didWork(boolean didWork) {
        return didWork ? DID_WORK : DID_NO_WORK;
    }
}

