package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.internal.snapshot.ValueSnapshotter;


public class MapValueSnapshot extends AbstractMapSnapshot<ValueSnapshot> implements ValueSnapshot {
    public MapValueSnapshot(ImmutableList<MapEntrySnapshot<ValueSnapshot>> entries) {
        super(entries);
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot newSnapshot = snapshotter.snapshot(value);
        if (equals(newSnapshot)) {
            return this;
        }
        return newSnapshot;
    }
}