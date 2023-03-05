package org.gradle.api.internal.provider;

import org.gradle.api.Action;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.gradle.api.provider.ValueSourceSpec;
import org.gradle.internal.Try;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

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
