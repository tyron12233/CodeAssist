package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.tasks.Buildable;

import java.util.Set;

public class BuildableBackedSetProvider<T extends Buildable, V> extends BuildableBackedProvider<Set<V>> {

    public BuildableBackedSetProvider(T buildable, Factory<Set<V>> valueFactory) {
        super(buildable, Cast.uncheckedCast(Set.class), valueFactory);
    }
}