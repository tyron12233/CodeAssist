package com.tyron.builder.internal.execution.caching;

public enum CachingDisabledReasonCategory {
    /**
     * Reason for disabled caching is not known.
     */
    UNKNOWN,

    /**
     * Caching has not been enabled for the build.
     */
    BUILD_CACHE_DISABLED,

    /**
     * Caching has not been enabled for the work.
     */
    NOT_CACHEABLE,

    /**
     * Condition enabling caching isn't satisfied.
     */
    ENABLE_CONDITION_NOT_SATISFIED,

    /**
     * Condition disabling caching satisfied.
     */
    DISABLE_CONDITION_SATISFIED,

    /**
     * The work has no outputs declared.
     */
    NO_OUTPUTS_DECLARED,

    /**
     * Work has declared output that is not cacheable.
     *
     * Reasons for non-cacheable outputs:
     * <ul>
     *     <li>an output contains a file tree,</li>
     *     <li>an output is not tracked.</li>
     * </ul>
     */
    NON_CACHEABLE_OUTPUT,

    /**
     * Work's outputs overlap with other work's.
     */
    OVERLAPPING_OUTPUTS,

    /**
     * The work has failed validation.
     */
    VALIDATION_FAILURE,

    /**
     * One of the work's inputs is not cacheable.
     *
     * Reasons for non-cacheable inputs:
     * <ul>
     *     <li>some type used as an input is loaded via an unknown classloader,</li>
     *     <li>a Java lambda was used as an input,</li>
     *     <li>an input is not tracked.</li>
     * </ul>
     *
     * @see <a href="https://docs.gradle.org/current/userguide/more_about_tasks.html#sec:how_does_it_work">How fingerprinting works</a>
     */
    NON_CACHEABLE_INPUTS
}
