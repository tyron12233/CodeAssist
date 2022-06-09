package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ValueSource;
import com.tyron.builder.api.provider.ValueSourceParameters;
import com.tyron.builder.api.provider.ValueSourceSpec;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;

/**
 * Service to create providers from {@link ValueSource}s.
 *
 * Notifies interested parties when values are obtained from their sources.
 *
 * @since 6.1
 */
@ServiceScope(Scopes.Build.class)
public interface ValueSourceProviderFactory {

    <T, P extends ValueSourceParameters> Provider<T> createProviderOf(
        Class<? extends ValueSource<T, P>> valueSourceType,
        Action<? super ValueSourceSpec<P>> configureAction
    );

    void addListener(Listener listener);

    void removeListener(Listener listener);

    <T, P extends ValueSourceParameters> Provider<T> instantiateValueSourceProvider(
        Class<? extends ValueSource<T, P>> valueSourceType,
        @Nullable Class<P> parametersType,
        @Nullable P parameters
    );

    @EventScope(Scopes.Build.class)
    interface Listener {

        <T, P extends ValueSourceParameters> void valueObtained(
            ObtainedValue<T, P> obtainedValue
        );

        interface ObtainedValue<T, P extends ValueSourceParameters> {

            Try<T> getValue();

            Class<? extends ValueSource<T, P>> getValueSourceType();

            Class<P> getValueSourceParametersType();

            @Nullable
            P getValueSourceParameters();
        }
    }
}
