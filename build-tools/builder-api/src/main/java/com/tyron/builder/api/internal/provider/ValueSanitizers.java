package com.tyron.builder.api.internal.provider;

import com.google.common.collect.ImmutableCollection;
import com.tyron.builder.api.internal.Cast;

import org.jetbrains.annotations.Nullable;

public class ValueSanitizers {
    private static final ValueSanitizer<Object> STRING_VALUE_SANITIZER = new ValueSanitizer<Object>() {
        @Override
        @Nullable
        public Object sanitize(@Nullable Object value) {
            if (!(value instanceof String) && value != null) {
                return value.toString();
            }
            return value;
        }
    };
    private static final ValueSanitizer<Object> IDENTITY_SANITIZER = new ValueSanitizer<Object>() {
        @Override
        @Nullable
        public Object sanitize(@Nullable Object value) {
            return value;
        }
    };
    private static final ValueCollector<Object> IDENTITY_VALUE_COLLECTOR = new ValueCollector<Object>() {
        @Override
        public void add(@Nullable Object value, ImmutableCollection.Builder<Object> dest) {
            dest.add(value);
        }

        @Override
        public void addAll(Iterable<?> values, ImmutableCollection.Builder<Object> dest) {
            dest.addAll(values);
        }
    };
    private static final ValueCollector<Object> STRING_VALUE_COLLECTOR = new ValueCollector<Object>() {
        @Override
        public void add(@Nullable Object value, ImmutableCollection.Builder<Object> dest) {
            dest.add(STRING_VALUE_SANITIZER.sanitize(value));
        }

        @Override
        public void addAll(Iterable<?> values, ImmutableCollection.Builder<Object> dest) {
            for (Object value : values) {
                add(value, dest);
            }
        }
    };

    public static <T> ValueSanitizer<T> forType(Class<? extends T> targetType) {
        if (String.class.equals(targetType)) {
            return Cast.uncheckedCast(STRING_VALUE_SANITIZER);
        } else {
            return Cast.uncheckedCast(IDENTITY_SANITIZER);
        }
    }

    public static <T> ValueCollector<T> collectorFor(Class<? extends T> elementType) {
        if (String.class.equals(elementType)) {
            return Cast.uncheckedCast(STRING_VALUE_COLLECTOR);
        } else {
            return Cast.uncheckedCast(IDENTITY_VALUE_COLLECTOR);
        }
    }
}