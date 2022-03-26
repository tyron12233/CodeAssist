package com.tyron.builder.api.internal.provider;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;

import org.jetbrains.annotations.Nullable;

class ValidatingValueCollector<T> implements ValueCollector<T> {
    private final Class<?> collectionType;
    private final Class<T> elementType;
    private final ValueSanitizer<T> sanitizer;

    ValidatingValueCollector(Class<?> collectionType, Class<T> elementType, ValueSanitizer<T> sanitizer) {
        this.collectionType = collectionType;
        this.elementType = elementType;
        this.sanitizer = sanitizer;
    }

    @Override
    public void add(@Nullable T value, ImmutableCollection.Builder<T> dest) {
        Preconditions.checkNotNull(
                value,
                "Cannot get the value of a property of type %s with element type %s as the source value contains a null element.",
                collectionType.getName(), elementType.getName());

        T sanitized = sanitizer.sanitize(value);
        if (!elementType.isInstance(sanitized)) {
            throw new IllegalArgumentException(String.format(
                    "Cannot get the value of a property of type %s with element type %s as the source value contains an element of type %s.",
                    collectionType.getName(), elementType.getName(), value.getClass().getName()));
        }
        dest.add(sanitized);
    }

    @Override
    public void addAll(Iterable<? extends T> values, ImmutableCollection.Builder<T> dest) {
        for (T value : values) {
            add(value, dest);
        }
    }
}