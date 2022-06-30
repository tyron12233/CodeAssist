package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

import java.nio.charset.StandardCharsets;

public class EnumValueSnapshot implements ValueSnapshot {
    private final String className;
    private final String name;

    public EnumValueSnapshot(Enum<?> value) {
        // Don't retain the value, to allow ClassLoader to be collected
        this.className = value.getClass().getName();
        this.name = value.name();
    }

    public EnumValueSnapshot(String className, String name) {
        this.className = className;
        this.name = name;
    }

    public String getClassName() {
        return className;
    }

    public String getName() {
        return name;
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        if (isEqualEnum(value)) {
            return this;
        }
        return snapshotter.snapshot(value);
    }

    private boolean isEqualEnum(Object value) {
        if (value instanceof Enum) {
            Enum<?> enumValue = (Enum<?>) value;
            if (enumValue.name().equals(name) && enumValue.getClass().getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(className, StandardCharsets.UTF_8);
        hasher.putString(name, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }

        EnumValueSnapshot other = (EnumValueSnapshot) obj;
        return className.equals(other.className) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return className.hashCode() ^ name.hashCode();
    }
}