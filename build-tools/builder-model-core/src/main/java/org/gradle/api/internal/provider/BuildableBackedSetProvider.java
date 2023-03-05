package org.gradle.api.internal.provider;

import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.api.Buildable;

import java.util.Set;

public class BuildableBackedSetProvider<T extends Buildable, V> extends BuildableBackedProvider<Set<V>> {

    public BuildableBackedSetProvider(T buildable, Factory<Set<V>> valueFactory) {
        super(buildable, Cast.uncheckedCast(Set.class), valueFactory);
    }
}