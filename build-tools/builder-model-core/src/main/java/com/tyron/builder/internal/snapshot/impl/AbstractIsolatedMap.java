package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

abstract public class AbstractIsolatedMap<T extends Map<Object, Object>> extends AbstractMapSnapshot<Isolatable<?>> implements Isolatable<T>, Factory<T> {
    public AbstractIsolatedMap(ImmutableList<MapEntrySnapshot<Isolatable<?>>> entries) {
        super(entries);
    }

    @Override
    public ValueSnapshot asSnapshot() {
        ImmutableList.Builder<MapEntrySnapshot<ValueSnapshot>> builder = ImmutableList.builderWithExpectedSize(entries.size());
        for (MapEntrySnapshot<Isolatable<?>> entry : entries) {
            builder.add(new MapEntrySnapshot<ValueSnapshot>(entry.getKey().asSnapshot(), entry.getValue().asSnapshot()));
        }
        return new MapValueSnapshot(builder.build());
    }

    @Override
    public T isolate() {
        T map = create();
        for (MapEntrySnapshot<Isolatable<?>> entry : getEntries()) {
            map.put(entry.getKey().isolate(), entry.getValue().isolate());
        }
        return map;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        return null;
    }
}