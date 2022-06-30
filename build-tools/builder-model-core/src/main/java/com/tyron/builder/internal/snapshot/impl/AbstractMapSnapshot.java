package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.hash.Hashable;

import java.nio.charset.StandardCharsets;

class AbstractMapSnapshot<T extends Hashable> implements Hashable {
    protected final ImmutableList<MapEntrySnapshot<T>> entries;

    public AbstractMapSnapshot(ImmutableList<MapEntrySnapshot<T>> entries) {
        this.entries = entries;
    }

    public ImmutableList<MapEntrySnapshot<T>> getEntries() {
        return entries;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString("Map", StandardCharsets.UTF_8);
        hasher.putInt(entries.size());
        for (MapEntrySnapshot<T> entry : entries) {
            entry.getKey().appendToHasher(hasher);
            entry.getValue().appendToHasher(hasher);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        AbstractMapSnapshot other = (AbstractMapSnapshot) obj;
        return entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }
}