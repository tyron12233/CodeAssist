package com.tyron.builder.api.internal.provider;

import java.util.Map;
import java.util.Set;

interface MapSupplier<K, V> extends ValueSupplier {
    Value<? extends Map<K, V>> calculateValue(ValueConsumer consumer);

    Value<? extends Set<K>> calculateKeys(ValueConsumer consumer);

    MapSupplier<K, V> plus(MapCollector<K, V> collector);

    ExecutionTimeValue<? extends Map<K, V>> calculateOwnExecutionTimeValue();
}