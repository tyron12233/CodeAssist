package com.tyron.builder.api.artifacts.transform;

/**
 * Marker interface for parameter objects to {@link TransformAction}s.
 *
 * <p>
 *     Parameter types should be interfaces, only declaring getters for {@link com.tyron.builder.api.provider.Property}-like objects.
 *     All getters must be annotated with an input annotation like {@link com.tyron.builder.api.tasks.Input} or {@link com.tyron.builder.api.tasks.InputFiles}.
 *     Normalization annotations such as {@link com.tyron.builder.api.tasks.PathSensitive} or {@link com.tyron.builder.api.tasks.Classpath} can be used as well.
 *     See the <a href="https://docs.gradle.org/current/userguide/more_about_tasks.html#table:incremental_build_annotations">table of incremental build property type annotations</a> for all annotations which can be used.
 *     Example:
 * </p>
 * <pre class='autoTested'>
 * public interface MyParameters extends TransformParameters {
 *     {@literal @}Input
 *     Property&lt;String&gt; getStringParameter();
 *     {@literal @}InputFiles
 *     ConfigurableFileCollection getInputFiles();
 * }
 * </pre>
 *
 * @since 5.3
 */
public interface TransformParameters {
    /**
     * Used for {@link TransformAction}s without parameters.
     *
     * <p>When {@link None} is used as parameters, calling {@link TransformAction#getParameters()} throws an exception.</p>
     *
     * @since 5.3
     */
    final class None implements TransformParameters {
        private None() {}
    }
}
