package org.gradle.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.snapshot.ValueSnapshot;
import org.gradle.internal.snapshot.ValueSnapshotter;

public class ArrayValueSnapshot extends AbstractArraySnapshot<ValueSnapshot> implements ValueSnapshot {
    public static final ArrayValueSnapshot EMPTY = new ArrayValueSnapshot(ImmutableList.of());

    public ArrayValueSnapshot(ImmutableList<ValueSnapshot> elements) {
        super(elements);
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot other = snapshotter.snapshot(value);
        if (isEqualArrayValueSnapshot(other)) {
            return this;
        }
        return other;
    }

    private boolean isEqualArrayValueSnapshot(ValueSnapshot other) {
        if (other instanceof ArrayValueSnapshot) {
            ArrayValueSnapshot otherArray = (ArrayValueSnapshot) other;
            if (elements.equals(otherArray.elements)) {
                return true;
            }
        }
        return false;
    }
}