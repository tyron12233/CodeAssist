package com.tyron.builder.api.internal.provider;

import java.util.Collection;

interface CollectionSupplier<T, C extends Collection<? extends T>> extends ValueSupplier {
    Value<? extends C> calculateValue(ValueConsumer consumer);

    CollectionSupplier<T, C> plus(Collector<T> collector);

    ExecutionTimeValue<? extends C> calculateExecutionTimeValue();
}
