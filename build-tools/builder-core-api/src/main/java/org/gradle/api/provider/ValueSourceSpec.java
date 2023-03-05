package org.gradle.api.provider;

import org.gradle.api.Action;
import org.gradle.api.Incubating;


/**
 * Base configuration for value source definitions.
 *
 * @see org.gradle.api.provider.ProviderFactory#of(Class, Action)
 * @param <P> The value source specific parameter type.
 * @since 6.1
 */
@Incubating
public interface ValueSourceSpec<P extends ValueSourceParameters> {

    /**
     * The parameters for the value source.
     *
     * @see org.gradle.api.provider.ProviderFactory#of(Class, Action)
     */
    P getParameters();

    /**
     * Configure the parameters for the value source.
     *
     * @see org.gradle.api.provider.ProviderFactory#of(Class, Action)
     */
    void parameters(Action<? super P> action);
}
