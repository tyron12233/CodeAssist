package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

public class SetValueSnapshot extends AbstractSetSnapshot<ValueSnapshot> implements ValueSnapshot {
    public SetValueSnapshot(ImmutableSet<ValueSnapshot> elements) {
        super(elements);
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot newSnapshot = snapshotter.snapshot(value);
        if (isEqualSetValueSnapshot(newSnapshot)) {
            return this;
        }
        return newSnapshot;
    }

    private boolean isEqualSetValueSnapshot(ValueSnapshot newSnapshot) {
        if (newSnapshot instanceof SetValueSnapshot) {
            SetValueSnapshot other = (SetValueSnapshot) newSnapshot;
            if (elements.equals(other.elements)) {
                return true;
            }
        }
        return false;
    }
}