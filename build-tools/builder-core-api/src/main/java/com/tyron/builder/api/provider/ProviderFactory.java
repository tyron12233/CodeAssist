package com.tyron.builder.api.provider;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.NonExtensible;
import com.tyron.builder.api.credentials.AwsCredentials;
import com.tyron.builder.api.credentials.Credentials;
import com.tyron.builder.api.credentials.PasswordCredentials;
import com.tyron.builder.api.file.FileContents;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.util.concurrent.Callable;
import java.util.function.BiFunction;

/**
 * A factory for creating instances of {@link Provider}.
 *
 * <p>
 * An instance of the factory can be injected into a task, plugin or other object by annotating a public constructor or property getter method with {@code javax.inject.Inject}.
 * It is also available via {@link com.tyron.builder.api.BuildProject#getProviders()} and {@link Settings#getProviders()}.
 *
 * @since 4.0
 */
@NonExtensible
@ServiceScope(Scopes.Build.class)
public interface ProviderFactory {

    /**
     * Creates a {@link Provider} whose value is calculated using the given {@link Callable}.
     *
     * <p>The provider is live and will call the {@link Callable} each time its value is queried. The {@link Callable} may return {@code null}, in which case the provider is considered to have no value.
     *
     * @param value The {@code java.util.concurrent.Callable} use to calculate the value.
     * @return The provider. Never returns null.
     */
    <T> Provider<T> provider(Callable<? extends T> value);

    /**
     * Creates a {@link Provider} whose value is fetched from the environment variable with the given name.
     *
     * @param variableName The name of the environment variable.
     * @return The provider. Never returns null.
     * @since 6.1
     */
    Provider<String> environmentVariable(String variableName);

    /**
     * Creates a {@link Provider} whose value is fetched from the environment variable with the given name.
     *
     * @param variableName The provider for the name of the environment variable; when the given provider has no value, the returned provider has no value.
     * @return The provider. Never returns null.
     * @since 6.1
     */
    Provider<String> environmentVariable(Provider<String> variableName);

    /**
     * Creates a {@link Provider} whose value is fetched from system properties using the given property name.
     *
     * @param propertyName the name of the system property
     * @return the provider for the system property, never returns null
     * @since 6.1
     */
    Provider<String> systemProperty(String propertyName);

    /**
     * Creates a {@link Provider} whose value is fetched from system properties using the given property name.
     *
     * @param propertyName the name of the system property
     * @return the provider for the system property, never returns null
     * @since 6.1
     */
    Provider<String> systemProperty(Provider<String> propertyName);

    /**
     * Creates a {@link Provider} whose value is fetched from the Gradle property of the given name.
     *
     * @param propertyName the name of the Gradle property
     * @return the provider for the Gradle property, never returns null
     * @since 6.2
     */
    Provider<String> gradleProperty(String propertyName);

    /**
     * Creates a {@link Provider} whose value is fetched from the Gradle property of the given name.
     *
     * @param propertyName the name of the Gradle property
     * @return the provider for the Gradle property, never returns null
     * @since 6.2
     */
    Provider<String> gradleProperty(Provider<String> propertyName);

    /**
     * Allows lazy access to the contents of the given file.
     *
     * When the file contents are read at configuration time the file is automatically considered
     * as an input to the configuration model.
     *
     * @param file the file whose contents to read.
     * @return an interface that allows lazy access to the contents of the given file.
     *
     * @see FileContents#getAsText()
     * @see FileContents#getAsBytes()
     *
     * @since 6.1
     */
    FileContents fileContents(RegularFile file);

    /**
     * Allows lazy access to the contents of the given file.
     *
     * When the file contents are read at configuration time the file is automatically considered
     * as an input to the configuration model.
     *
     * @param file provider of the file whose contents to read.
     * @return an interface that allows lazy access to the contents of the given file.
     *
     * @see FileContents#getAsText()
     * @see FileContents#getAsBytes()
     *
     * @since 6.1
     */
    FileContents fileContents(Provider<RegularFile> file);

    /**
     * Creates a {@link Provider} whose value is obtained from the given {@link ValueSource}.
     *
     * @param valueSourceType the type of the {@link ValueSource}
     * @param configuration action to configure the parameters to the given {@link ValueSource}
     * @return the provider, never returns null
     * @since 6.1
     */
    @Incubating
    <T, P extends ValueSourceParameters>
    Provider<T> of(
            Class<? extends ValueSource<T, P>> valueSourceType,
            Action<? super ValueSourceSpec<P>> configuration
    );

    /**
     * Creates a {@link Provider} for the given {@link Credentials} type.
     *
     * <p>
     * The provider returned by this method should be attached to a task's input property.
     * This way, the presence of credentials will be validated before any of the tasks are executed if and only if the task with credentials property is to be executed.
     *
     * <p>
     * Values for the requested Credentials type will be sourced from the project's properties using the pattern "identity" + credentials field.
     * For example, {@link PasswordCredentials} provider with identity "myService" will look for properties named "myServiceUsername" and "myServicePassword".
     *
     * <p>
     * The following credential types are currently supported:
     * <ul>
     * <li>{@link PasswordCredentials}</li>
     * <li>{@link AwsCredentials}</li>
     * </ul>
     *
     * @param credentialsType type of credentials to be provided.
     * @param identity identity to be associated with the credentials.
     * @return The provider. Never returns null.
     *
     * @since 6.6
     */
    <T extends Credentials> Provider<T> credentials(Class<T> credentialsType, String identity);

    /**
     * Creates a {@link Provider} for the given {@link Credentials} type.
     *
     * <p>
     * The provider returned by this method should be attached to a task's input property.
     * This way, the presence of credentials will be validated before any of the tasks are executed if and only if the task with credentials property is to be executed.
     *
     * <p>
     * Values for the requested Credentials type will be sourced from the project's properties using the pattern "identity" + credentials field.
     * For example, {@link PasswordCredentials} provider with identity "myService" will look for properties named "myServiceUsername" and "myServicePassword".
     *
     * <p>
     * The following credential types are currently supported:
     * <ul>
     * <li>{@link PasswordCredentials}</li>
     * <li>{@link AwsCredentials}</li>
     * </ul>
     *
     * @param credentialsType type of credentials to be provided.
     * @param identity a provider returning the identity to be associated with the credentials.
     * @return The provider. Never returns null.
     *
     * @since 6.6
     */
    <T extends Credentials> Provider<T> credentials(Class<T> credentialsType, Provider<String> identity);

    /**
     * Returns a provider which value will be computed by combining a provider value with another
     * provider value using the supplied combiner function.
     *
     * If the supplied providers represents a task or the output of a task, the resulting provider
     * will carry the dependency information.
     *
     * @param first the first provider to combine with
     * @param second the second provider to combine with
     * @param combiner the combiner of values
     * @param <A> the type of the first provider
     * @param <B> the type of the second provider
     * @param <R> the type of the result of the combiner
     * @return a combined provider
     *
     * @since 6.6
     */
    <A, B, R> Provider<R> zip(Provider<A> first, Provider<B> second, BiFunction<A, B, R> combiner);
}
