package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import org.jetbrains.annotations.Nullable;

/**
 * A isolated immutable scalar value. Should only be used for immutable JVM provided or core Gradle types.
 *
 * @param <T>
 */
abstract class AbstractIsolatableScalarValue<T> extends AbstractScalarValueSnapshot<T> implements Isolatable<T> {
    public AbstractIsolatableScalarValue(T value) {
        super(value);
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return this;
    }

    @Override
    public T isolate() {
        return getValue();
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isInstance(getValue())) {
            return type.cast(getValue());
        }
        return null;
    }
}