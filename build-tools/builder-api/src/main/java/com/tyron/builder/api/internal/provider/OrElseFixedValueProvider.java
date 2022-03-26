package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;

class OrElseFixedValueProvider<T> extends AbstractProviderWithValue<T> {
    private final ProviderInternal<? extends T> provider;
    private final T fallbackValue;

    public OrElseFixedValueProvider(ProviderInternal<? extends T> provider, T fallbackValue) {
        this.provider = provider;
        this.fallbackValue = fallbackValue;
    }

    @Override
    public String toString() {
        return String.format("or(%s, fixed(%s))", provider, fallbackValue);
    }

    @Nullable
    @Override
    public Class<T> getType() {
        //noinspection unchecked
        return (Class<T>) provider.getType();
    }

    @Override
    public ValueProducer getProducer() {
        return new OrElseValueProducer(provider, null, ValueProducer.unknown());
    }

    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        ExecutionTimeValue<? extends T> value = provider.calculateExecutionTimeValue();
        if (value.isMissing()) {
            // Use fallback value
            return ExecutionTimeValue.fixedValue(fallbackValue);
        } else if (value.isFixedValue()) {
            // Result is fixed value, use it
            return value;
        } else {
            // Value is changing, so keep the logic
            return ExecutionTimeValue.changingValue(new OrElseFixedValueProvider<>(value.getChangingValue(), fallbackValue));
        }
    }

    @Override
    protected Value<? extends T> calculateOwnValue(ValueConsumer consumer) {
        Value<? extends T> value = provider.calculateValue(consumer);
        if (value.isMissing()) {
            return Value.of(fallbackValue);
        } else {
            return value;
        }
    }
}
