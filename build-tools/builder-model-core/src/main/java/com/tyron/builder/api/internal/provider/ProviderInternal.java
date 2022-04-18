package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

public interface ProviderInternal<T> extends Provider<T>, ValueSupplier, TaskDependencyContainer {
    /**
     * Return the upper bound on the type of all values that this provider may produce, if known.
     *
     * This could probably move to the public API.
     */
    @Nullable
    Class<T> getType();

    @Override
    <S> ProviderInternal<S> map(Transformer<? extends S, ? super T> transformer);

    /**
     * Calculates the current value of this provider.
     */
    ValueSupplier.Value<? extends T> calculateValue(ValueConsumer consumer);

    /**
     * Returns a view of this provider that can be used to supply a value to a {@link org.gradle.api.provider.Property} instance.
     */
    ProviderInternal<T> asSupplier(DisplayName owner, Class<? super T> targetType, ValueSanitizer<? super T> sanitizer);

    /**
     * Returns a copy of this provider with a final value. The returned value is used to replace this provider by a property when the property is finalized.
     */
    ProviderInternal<T> withFinalValue(ValueConsumer consumer);

    /**
     * Calculates the state of this provider that is required at execution time. The state is serialized to the configuration cache, and recreated as a {@link Provider} implementation
     * when the cache is read.
     *
     * <p>When the value and value content of this provider is known at the completion of configuration, then returns a fixed value or missing value.
     * For example, a String @Input property of a task might have a value that is calculated at configuration time, but once configured does not change.
     *
     * <p>When the value or value content of this provider is not known until execution time then returns a {@link Provider} representing the calculation to perform at execution time.
     * For example, the value content of an @InputFile property of a task is not known when that input file is the output of another a task.
     * The provider returned by this method may or not be the same instance as this provider. Generally, it is better to simplify any provider chains to replace calculations with fixed values and to remove
     * intermediate steps.
     */
    ExecutionTimeValue<? extends T> calculateExecutionTimeValue();

    default <U, R> Provider<R> zip(Provider<U> right, BiFunction<? super T, ? super U, ? extends R> combiner) {
        return new BiProvider<>(this, right, combiner);
    }
}