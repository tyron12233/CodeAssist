package com.tyron.builder.workers;

/**
 * Marker interface for parameter objects to {@link WorkAction}s.
 *
 * <p>
 *     Parameter types should be interfaces, only declaring getters for {@link org.gradle.api.provider.Property}-like objects.
 *     Example:
 * </p>
 * <pre class='autoTested'>
 * public interface MyParameters extends WorkParameters {
 *     Property&lt;String&gt; getStringParameter();
 *     ConfigurableFileCollection getFiles();
 * }
 * </pre>
 *
 * @since 5.6
 */
public interface WorkParameters {
    /**
     * Used for work actions without parameters.
     *
     * <p>When {@link None} is used as parameters, calling {@link WorkAction#getParameters()} throws an exception.</p>
     *
     * @since 5.6
     */
    final class None implements WorkParameters {
        private None() {}
    }
}
