package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;

/**
 * Isolates a Serialized value and is a snapshot for that value.
 */
public class IsolatedSerializedValueSnapshot extends SerializedValueSnapshot implements Isolatable<Object> {
    private final Class<?> originalClass;

    public IsolatedSerializedValueSnapshot(@Nullable HashCode implementationHash, byte[] serializedValue, Class<?> originalClass) {
        super(implementationHash, serializedValue);
        this.originalClass = originalClass;
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return new SerializedValueSnapshot(getImplementationHash(), getValue());
    }

    @Override
    public Object isolate() {
        return populateClass(originalClass);
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isAssignableFrom(originalClass)) {
            return type.cast(isolate());
        }
        if (type.getName().equals(originalClass.getName())) {
            return type.cast(populateClass(type));
        }
        return null;
    }

    public Class<?> getOriginalClass() {
        return originalClass;
    }
}
