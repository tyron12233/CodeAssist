package com.tyron.builder.api.internal.provider;

import com.google.common.collect.ImmutableCollection;
import com.tyron.builder.api.Action;

import java.util.Map;

/**
 * A supplier of zero or more mappings from value of type {@link K} to value of type {@link V}.
 */
public interface MapCollector<K, V> extends ValueSupplier {

    Value<Void> collectEntries(ValueConsumer consumer, MapEntryCollector<K, V> collector, Map<K, V> dest);

    Value<Void> collectKeys(ValueConsumer consumer, ValueCollector<K> collector, ImmutableCollection.Builder<K> dest);

    void calculateExecutionTimeValue(Action<ExecutionTimeValue<? extends Map<? extends K, ? extends V>>> visitor);
}