package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.state.Managed;
import com.tyron.builder.internal.state.ManagedFactory;
import com.tyron.builder.internal.state.ManagedFactoryRegistry;

import org.jetbrains.annotations.Nullable;

public class IsolatedImmutableManagedValue extends AbstractIsolatableScalarValue<Managed> {
    private final ManagedFactoryRegistry managedFactoryRegistry;

    public IsolatedImmutableManagedValue(Managed managed, ManagedFactoryRegistry managedFactoryRegistry) {
        super(managed);
        this.managedFactoryRegistry = managedFactoryRegistry;
    }

    @Override
    public ValueSnapshot asSnapshot() {
        return new ImmutableManagedValueSnapshot(getValue().publicType().getName(), (String) getValue().unpackState());
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        asSnapshot().appendToHasher(hasher);
    }

    @Nullable
    @Override
    public <S> S coerce(Class<S> type) {
        if (type.isInstance(getValue())) {
            return type.cast(getValue());
        }
        ManagedFactory factory = managedFactoryRegistry.lookup(getValue().getFactoryId());
        if (factory == null) {
            return null;
        } else {
            return type.cast(factory.fromState(type, getValue().unpackState()));
        }
    }
}