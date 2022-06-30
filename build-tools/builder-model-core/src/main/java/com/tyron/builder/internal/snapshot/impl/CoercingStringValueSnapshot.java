package com.tyron.builder.internal.snapshot.impl;

import com.tyron.builder.api.Named;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.internal.Cast;

import javax.annotation.Nullable;

public class CoercingStringValueSnapshot extends StringValueSnapshot {
    private final NamedObjectInstantiator instantiator;

    public CoercingStringValueSnapshot(String value, NamedObjectInstantiator instantiator) {
        super(value);
        this.instantiator = instantiator;
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isInstance(getValue())) {
            return type.cast(this);
        }
        if (type.isEnum()) {
            return type.cast(Enum.valueOf(Cast.uncheckedNonnullCast(type.asSubclass(Enum.class)), getValue()));
        }
        if (Named.class.isAssignableFrom(type)) {
            return type.cast(instantiator.named(type.asSubclass(Named.class), getValue()));
        }
        if (Integer.class.equals(type)) {
            return type.cast(Integer.parseInt(getValue()));
        }
        return null;
    }
}
