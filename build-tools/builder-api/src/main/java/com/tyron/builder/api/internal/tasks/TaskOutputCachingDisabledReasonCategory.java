package com.tyron.builder.api.internal.tasks;

public enum TaskOutputCachingDisabledReasonCategory {
    /**
     * Reason for disabled caching is not known.
     */
    UNKNOWN,

    /**
     * Caching has not been enabled for the build.
     */
    BUILD_CACHE_DISABLED,

    /**
     * Caching has not been enabled for the task.
     */
    NOT_ENABLED_FOR_TASK,

    /**
     * The task has no outputs declared.
     */
    NO_OUTPUTS_DECLARED,

    /**
     * Task has a {@link org.gradle.api.file.FileTree} or {@link org.gradle.api.internal.file.collections.DirectoryFileTree} as an output.
     *
     * @since 5.0
     */
    NON_CACHEABLE_TREE_OUTPUT,

    /**
     * Caching is disabled for the task via {@link org.gradle.api.tasks.TaskOutputs#cacheIf(Spec)}.
     */
    CACHE_IF_SPEC_NOT_SATISFIED,

    /**
     * Caching is disabled for the task via {@link org.gradle.api.tasks.TaskOutputs#doNotCacheIf(String, Spec)}.
     */
    DO_NOT_CACHE_IF_SPEC_SATISFIED,

    /**
     * Task's outputs overlap with another task. As Gradle cannot safely determine which task each output file belongs to it disables caching.
     */
    OVERLAPPING_OUTPUTS,

    /**
     * The task failed validation.
     */
    VALIDATION_FAILURE

}