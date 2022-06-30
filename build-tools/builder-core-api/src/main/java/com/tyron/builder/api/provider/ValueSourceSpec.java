package com.tyron.builder.api.provider;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;


/**
 * Base configuration for value source definitions.
 *
 * @see com.tyron.builder.api.provider.ProviderFactory#of(Class, Action)
 * @param <P> The value source specific parameter type.
 * @since 6.1
 */
@Incubating
public interface ValueSourceSpec<P extends ValueSourceParameters> {

    /**
     * The parameters for the value source.
     *
     * @see com.tyron.builder.api.provider.ProviderFactory#of(Class, Action)
     */
    P getParameters();

    /**
     * Configure the parameters for the value source.
     *
     * @see com.tyron.builder.api.provider.ProviderFactory#of(Class, Action)
     */
    void parameters(Action<? super P> action);
}
