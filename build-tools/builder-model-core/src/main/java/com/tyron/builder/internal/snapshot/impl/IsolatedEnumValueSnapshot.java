package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.snapshot.ValueSnapshot;

import javax.annotation.Nullable;

/**
 * Isolates an Enum value and is a snapshot for that value.
 */
public class IsolatedEnumValueSnapshot extends EnumValueSnapshot implements Isolatable<Enum> {
    private final Enum<?> value;

    public IsolatedEnumValueSnapshot(Enum<?> value) {
        super(value);
        this.value = value;
    }

    public Enum<?> getValue() {
        return value;
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return new EnumValueSnapshot(value);
    }

    @Override
    public Enum isolate() {
        return value;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isAssignableFrom(value.getClass())) {
            return type.cast(value);
        }
        if (type.isEnum() && type.getName().equals(value.getClass().getName())) {
            return type.cast(Enum.valueOf(Cast.uncheckedNonnullCast(type.asSubclass(Enum.class)), value.name()));
        }
        return null;
    }
}
