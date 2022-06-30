package com.tyron.builder.api.provider;

import com.tyron.builder.api.Incubating;

/**
 * Marker interface for parameter objects to {@link ValueSource}s.
 *
 * <p>
 * Parameter types should be interfaces, only declaring getters for {@link com.tyron.builder.api.provider.Property}-like objects.
 * </p>
 * <pre class='autoTested'>
 * public interface MyParameters extends ValueSourceParameters {
 *     Property&lt;String&gt; getStringParameter();
 * }
 * </pre>
 *
 * @since 6.1
 */
@Incubating
public interface ValueSourceParameters {
    /**
     * Used for sources without parameters.
     *
     * @since 6.1
     */
    @Incubating
    final class None implements ValueSourceParameters {
        private None() {
        }
    }
}
