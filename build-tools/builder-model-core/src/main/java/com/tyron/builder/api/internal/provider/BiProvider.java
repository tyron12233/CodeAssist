package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

class BiProvider<R, A, B> extends AbstractMinimalProvider<R> {

    private final BiFunction<? super A, ? super B, ? extends R> combiner;
    private final ProviderInternal<A> left;
    private final ProviderInternal<B> right;

    public BiProvider(Provider<A> left, Provider<B> right, BiFunction<? super A, ? super B, ? extends R> combiner) {
        this.combiner = combiner;
        this.left = Providers.internal(left);
        this.right = Providers.internal(right);
    }

    @Override
    public String toString() {
        return String.format("and(%s, %s)", left, right);
    }

    @Override
    public ExecutionTimeValue<? extends R> calculateExecutionTimeValue() {
        return isChangingValue(left) || isChangingValue(right)
                ? ExecutionTimeValue.changingValue(this)
                : super.calculateExecutionTimeValue();
    }

    private boolean isChangingValue(ProviderInternal<?> provider) {
        return provider.calculateExecutionTimeValue().isChangingValue();
    }

    @Override
    protected Value<? extends R> calculateOwnValue(ValueConsumer consumer) {
        Value<? extends A> lv = left.calculateValue(consumer);
        if (lv.isMissing()) {
            return lv.asType();
        }
        Value<? extends B> rv = right.calculateValue(consumer);
        if (rv.isMissing()) {
            return rv.asType();
        }
        return Value.of(combiner.apply(lv.get(), rv.get()));
    }

    @Nullable
    @Override
    public Class<R> getType() {
        // Could do a better job of inferring this
        return null;
    }

    @Override
    public ValueProducer getProducer() {
        return new PlusProducer(left.getProducer(), right.getProducer());
    }
}