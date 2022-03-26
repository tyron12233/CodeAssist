package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.file.FileCollection;

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

    /**
     * Returns the output files of this task.
     *
     * @return The output files. Returns an empty collection if this task has no output files.
     */
    FileCollection getFiles();

    /**
     * Registers some output files for this task.
     *
     * <p>When the given {@code paths} is a {@link java.util.Map}, then each output file
     * will be associated with an identity.
     * The keys of the map must be non-empty strings.
     * The values of the map will be evaluated to individual files as per
     * {@link org.gradle.api.Project#file(Object)}.</p>
     *
     * <p>Otherwise the given files will be evaluated as per
     * {@link org.gradle.api.Project#files(Object...)}.</p>
     *
     * @param paths The output files.
     *
     * @see CacheableTask
     */
    TaskOutputFilePropertyBuilder files(Object... paths);

    /**
     * Registers some output directories for this task.
     *
     * <p>When the given {@code paths} is a {@link java.util.Map}, then each output directory
     * will be associated with an identity.
     * The keys of the map must be non-empty strings.
     * The values of the map will be evaluated to individual directories as per
     * {@link org.gradle.api.Project#file(Object)}.</p>
     *
     * <p>Otherwise the given directories will be evaluated as per
     * {@link org.gradle.api.Project#files(Object...)}.</p>
     *
     * @param paths The output files.
     *
     * @see CacheableTask
     *
     * @since 3.3
     */
    TaskOutputFilePropertyBuilder dirs(Object... paths);

    /**
     * Registers some output file for this task.
     *
     * @param path The output file. The given path is evaluated as per {@link org.gradle.api.Project#file(Object)}.
     * @return a property builder to further configure this property.
     */
    TaskOutputFilePropertyBuilder file(Object path);

    /**
     * Registers an output directory for this task.
     *
     * @param path The output directory. The given path is evaluated as per {@link org.gradle.api.Project#file(Object)}.
     * @return a property builder to further configure this property.
     */
    TaskOutputFilePropertyBuilder dir(Object path);

}
