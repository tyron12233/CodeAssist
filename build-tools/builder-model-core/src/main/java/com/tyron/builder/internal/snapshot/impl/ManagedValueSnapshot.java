package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

public class ManagedValueSnapshot extends AbstractManagedValueSnapshot<ValueSnapshot> implements ValueSnapshot {
    private final String className;

    public ManagedValueSnapshot(String className, ValueSnapshot state) {
        super(state);
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }
        ManagedValueSnapshot other = (ManagedValueSnapshot) obj;
        return className.equals(other.className);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ className.hashCode();
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot snapshot = snapshotter.snapshot(value);
        if (snapshot.equals(this)) {
            return this;
        }
        return snapshot;
    }
}