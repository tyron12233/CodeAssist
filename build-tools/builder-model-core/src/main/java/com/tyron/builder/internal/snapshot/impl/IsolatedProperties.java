package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.isolation.Isolatable;

import javax.annotation.Nullable;
import java.util.Properties;

public class IsolatedProperties extends AbstractIsolatedMap<Properties> {
    public IsolatedProperties(ImmutableList<MapEntrySnapshot<Isolatable<?>>> entries) {
        super(entries);
    }

    @Nullable
    @Override
    public Properties create() {
        return new Properties();
    }
}

