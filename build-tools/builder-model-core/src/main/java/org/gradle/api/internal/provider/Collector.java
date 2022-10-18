package org.gradle.api.internal.provider;


import com.google.common.collect.ImmutableCollection;
import org.gradle.api.Action;

/**
 * A supplier of zero or more values of type {@link T}.
 */
public interface Collector<T> extends ValueSupplier {
    Value<Void> collectEntries(ValueConsumer consumer, ValueCollector<T> collector, ImmutableCollection.Builder<T> dest);

    int size();

    void calculateExecutionTimeValue(Action<? super ExecutionTimeValue<? extends Iterable<? extends T>>> visitor);
}