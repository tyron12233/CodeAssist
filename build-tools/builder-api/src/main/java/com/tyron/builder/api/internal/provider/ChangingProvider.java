package com.tyron.builder.api.internal.provider;

import java.io.Serializable;
import java.util.concurrent.Callable;

/**
 * A provider whose value is computed by a {@link Callable}.
 *
 * The given {@link Callable} is stored to the configuration cache, so it must only hold references
 * to configuration cache safe state.
 *
 * Task dependencies attached to the computed value are ignored by this provider.
 *
 * <h3>Configuration Cache Behavior</h3>
 * <b>Lazy</b>. The given {@link Callable} is stored to the cache so the value can be recomputed on each run.
 */
class ChangingProvider<T> extends DefaultProvider<T> {

    public <CALLABLE extends Callable<T> & Serializable> ChangingProvider(CALLABLE value) {
        super(value);
    }

    @Override
    public String toString() {
        return "changing(?)";
    }

    @Override
    public ValueProducer getProducer() {
        return ValueProducer.UNKNOWN_PRODUCER;
    }

    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        return ExecutionTimeValue.changingValue(this);
    }
}