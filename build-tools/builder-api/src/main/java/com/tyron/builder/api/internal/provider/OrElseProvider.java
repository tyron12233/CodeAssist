package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;


class OrElseProvider<T> extends AbstractMinimalProvider<T> {
    private final ProviderInternal<T> left;
    private final ProviderInternal<? extends T> right;

    public OrElseProvider(ProviderInternal<T> left, ProviderInternal<? extends T> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public String toString() {
        return String.format("or(%s, %s)", left, right);
    }

    @Nullable
    @Override
    public Class<T> getType() {
        return left.getType();
    }

    @Override
    public ValueProducer getProducer() {
        return new OrElseValueProducer(left, right, right.getProducer());
    }

    @Override
    public boolean calculatePresence(ValueConsumer consumer) {
        return left.calculatePresence(consumer) || right.calculatePresence(consumer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        ExecutionTimeValue<? extends T> leftValue = left.calculateExecutionTimeValue();
        if (leftValue.isFixedValue()) {
            return leftValue;
        }
        ExecutionTimeValue<? extends T> rightValue = right.calculateExecutionTimeValue();
        if (leftValue.isMissing()) {
            return rightValue;
        }
        if (rightValue.isMissing()) {
            // simplify
            return leftValue;
        }
        return ExecutionTimeValue.changingValue(
                new OrElseProvider(
                        leftValue.getChangingValue(),
                        rightValue.toProvider()
                )
        );
    }

    @Override
    protected Value<? extends T> calculateOwnValue(ValueConsumer consumer) {
        Value<? extends T> leftValue = left.calculateValue(consumer);
        if (!leftValue.isMissing()) {
            return leftValue;
        }
        Value<? extends T> rightValue = right.calculateValue(consumer);
        if (!rightValue.isMissing()) {
            return rightValue;
        }
        return leftValue.addPathsFrom(rightValue);
    }
}