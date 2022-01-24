package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

import java.util.function.Predicate;

public interface TaskOutputs {

    /**
     * <p>
     *     Adds a predicate to determine whether previous outputs of this task can be reused.
     *     The given spec is evaluated at task execution time.
     *     If the spec returns false, previous outputs of this task cannot be reused and the task will be executed.
     *     That means the task is out-of-date and no outputs will be loaded from the build cache.
     * </p>
     *
     * <p>
     *     You can add multiple such predicates.
     *     The task outputs cannot be reused when any predicate returns false.
     * </p>
     *
     * @param upToDateSpec The spec to use to determine whether the task outputs are up-to-date.
     */
    void upToDateWhen(Predicate<? super Task> upToDateSpec);

    /**
     * <p>Cache the results of the task only if the given spec is satisfied. If the spec is not satisfied,
     * the results of the task will not be cached.</p>
     *
     * <p>You may add multiple such predicates. The results of the task are not cached if any of the predicates return {@code false},
     * or if any of the predicates passed to {@link #doNotCacheIf(String, Spec)} returns {@code true}. If {@code cacheIf()} is not specified,
     * the task will not be cached unless the {@literal @}{@link CacheableTask} annotation is present on the task type.</p>
     *
     * <p>Consider using {@link #cacheIf(String, Spec)} instead for also providing a reason for enabling caching.</p>
     *
     * @param spec specifies if the results of the task should be cached.
     *
     * @since 3.0
     */
    void cacheIf(Predicate<? super Task> spec);

    /**
     * <p>Cache the results of the task only if the given spec is satisfied. If the spec is not satisfied,
     * the results of the task will not be cached.</p>
     *
     * <p>You may add multiple such predicates. The results of the task are not cached if any of the predicates return {@code false},
     * or if any of the predicates passed to {@link #doNotCacheIf(String, Spec)} returns {@code true}. If {@code cacheIf()} is not specified,
     * the task will not be cached unless the {@literal @}{@link CacheableTask} annotation is present on the task type.</p>
     *
     * @param cachingEnabledReason the reason why caching would be enabled by the spec.
     * @param spec specifies if the results of the task should be cached.
     *
     * @since 3.4
     */
    void cacheIf(String cachingEnabledReason, final Predicate<? super Task> spec);

    /**
     * Returns true if this task has declared any outputs. Note that a task may be able to produce output files and
     * still have an empty set of output files.
     *
     * @return true if this task has declared any outputs, otherwise false.
     */
    boolean getHasOutput();

}
