package org.gradle.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.isolation.Isolatable;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

public class IsolatedMap extends AbstractIsolatedMap<Map<Object, Object>> {
    public IsolatedMap(ImmutableList<MapEntrySnapshot<Isolatable<?>>> entries) {
        super(entries);
    }

    @Nullable
    @Override
    public Map<Object, Object> create() {
        return new LinkedHashMap<>(getEntries().size());
    }
}
