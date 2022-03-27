package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.DisplayName;

class TypeSanitizingTransformer<T> implements Transformer<T, T> {
    private final DisplayName owner;
    private final ValueSanitizer<? super T> sanitizer;
    private final Class<? super T> targetType;

    public TypeSanitizingTransformer(DisplayName owner, ValueSanitizer<? super T> sanitizer, Class<? super T> targetType) {
        this.owner = owner;
        this.sanitizer = sanitizer;
        this.targetType = targetType;
    }

    @Override
    public String toString() {
        return "check-type()";
    }

    @Override
    public T transform(T t) {
        T v = Cast.uncheckedCast(sanitizer.sanitize(t));
        if (targetType.isInstance(v)) {
            return v;
        }
        throw new IllegalArgumentException(String.format("Cannot get the value of %s of type %s as the provider associated with this property returned a value of type %s.", owner.getDisplayName(), targetType.getName(), v.getClass().getName()));
    }
}