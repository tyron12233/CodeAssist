package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.concurrent.Callable;

public class Providers {
    private static final NoValueProvider<Object> NULL_PROVIDER = new NoValueProvider<>(ValueSupplier.Value.MISSING);

    public static final Provider<Boolean> TRUE = of(true);
    public static final Provider<Boolean> FALSE = of(false);

    public static <T> ProviderInternal<T> fixedValue(DisplayName owner, T value, Class<T> targetType, ValueSanitizer<T> sanitizer) {
        value = sanitizer.sanitize(value);
        if (!targetType.isInstance(value)) {
            throw new IllegalArgumentException(String.format("Cannot set the value of %s of type %s using an instance of type %s.", owner.getDisplayName(), targetType.getName(), value.getClass().getName()));
        }
        return new FixedValueProvider<>(value);
    }

    public static <T> ProviderInternal<T> nullableValue(ValueSupplier.Value<? extends T> value) {
        if (value.isMissing()) {
            if (value.getPathToOrigin().isEmpty()) {
                return notDefined();
            } else {
                return new NoValueProvider<>(value);
            }
        } else {
            return of(value.get());
        }
    }

    public static <T> ProviderInternal<T> notDefined() {
        //noinspection unchecked
        return (ProviderInternal<T>) NULL_PROVIDER;
    }

    public static <T> ProviderInternal<T> of(T value) {
        if (value == null) {
            throw new IllegalArgumentException();
        }
        return new FixedValueProvider<>(value);
    }

    public static <T> ProviderInternal<T> internal(final Provider<T> value) {
        return (ProviderInternal<T>) value;
    }

    public static <T> ProviderInternal<T> ofNullable(@Nullable T value) {
        if (value == null) {
            return notDefined();
        } else {
            return of(value);
        }
    }

    public interface SerializableCallable<V> extends Callable<V>, Serializable {
    }

    public static <T> ProviderInternal<T> changing(SerializableCallable<T> value) {
        return new ChangingProvider<>(value);
    }

    public static class FixedValueProvider<T> extends AbstractProviderWithValue<T> {
        private final T value;

        FixedValueProvider(T value) {
            this.value = value;
        }

        @Nullable
        @Override
        public Class<T> getType() {
            //noinspection unchecked
            return (Class<T>) value.getClass();
        }

        @Override
        protected Value<? extends T> calculateOwnValue(ValueConsumer consumer) {
            return Value.of(value);
        }

        @Override
        public ProviderInternal<T> withFinalValue(ValueConsumer consumer) {
            return this;
        }

        @Override
        public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
            return ExecutionTimeValue.fixedValue(value);
        }

        @Override
        public String toString() {
            return String.format("fixed(%s, %s)", getType(), value);
        }
    }

    public static class FixedValueWithChangingContentProvider<T> extends FixedValueProvider<T> {
        public FixedValueWithChangingContentProvider(T value) {
            super(value);
        }

        @Override
        public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
            return super.calculateExecutionTimeValue().withChangingContent();
        }
    }

    private static class NoValueProvider<T> extends AbstractMinimalProvider<T> {
        private final Value<? extends T> value;

        public NoValueProvider(Value<? extends T> value) {
            assert value.isMissing();
            this.value = value;
        }

        @Override
        public Value<? extends T> calculateValue(ValueConsumer consumer) {
            return value;
        }

        @Override
        public boolean isImmutable() {
            return true;
        }

        @Nullable
        @Override
        public Class<T> getType() {
            return null;
        }

        @Override
        protected Value<T> calculateOwnValue(ValueConsumer consumer) {
            return Value.missing();
        }

        @Override
        public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
            return ExecutionTimeValue.missing();
        }

        @Override
        public <S> ProviderInternal<S> map(Transformer<? extends S, ? super T> transformer) {
            return (ProviderInternal<S>) this;
        }

        @Override
        public boolean isPresent() {
            return false;
        }

        @Override
        public boolean calculatePresence(ValueConsumer consumer) {
            return false;
        }

        @Override
        public ProviderInternal<T> asSupplier(DisplayName owner, Class<? super T> targetType, ValueSanitizer<? super T> sanitizer) {
            return this;
        }

        @Override
        public ProviderInternal<T> withFinalValue(ValueConsumer consumer) {
            return this;
        }

        @Override
        public Provider<T> orElse(T value) {
            return Providers.of(value);
        }

        @Override
        public Provider<T> orElse(Provider<? extends T> provider) {
            //noinspection unchecked
            return (Provider<T>) provider;
        }

        @Override
        public String toString() {
            return "undefined";
        }
    }
}