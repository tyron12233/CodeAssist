package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

/**
 * A snapshot of an immutable scalar value. Should only be used for immutable JVM provided or core Gradle types.
 *
 * @param <T>
 */
abstract class AbstractScalarValueSnapshot<T> implements ValueSnapshot {
    private final T value;

    public AbstractScalarValueSnapshot(T value) {
        this.value = value;
    }

    public T getValue() {
        return value;
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        if (this.value.equals(value)) {
            return this;
        }
        ValueSnapshot snapshot = snapshotter.snapshot(value);
        if (snapshot.equals(this)) {
            return this;
        }
        return snapshot;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        AbstractScalarValueSnapshot other = (AbstractScalarValueSnapshot) obj;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}