package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;


public class ImmutableManagedValueSnapshot implements ValueSnapshot {
    private final String className;
    private final String value;

    public ImmutableManagedValueSnapshot(String className, String value) {
        this.className = className;
        this.value = value;
    }

    public String getClassName() {
        return className;
    }

    public String getValue() {
        return value;
    }

    @Override
    public ValueSnapshot snapshot(@Nullable Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot snapshot = snapshotter.snapshot(value);
        if (equals(snapshot)) {
            return this;
        }
        return snapshot;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        ImmutableManagedValueSnapshot other = (ImmutableManagedValueSnapshot) obj;
        return other.className.equals(className) && other.value.equals(value);
    }

    @Override
    public int hashCode() {
        return className.hashCode() ^ value.hashCode();
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(className, StandardCharsets.UTF_8);
        hasher.putString(value, StandardCharsets.UTF_8);
    }
}