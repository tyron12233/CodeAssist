package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;

public class BooleanValueSnapshot extends AbstractIsolatableScalarValue<Boolean> {
    public static final BooleanValueSnapshot TRUE = new BooleanValueSnapshot(true);
    public static final BooleanValueSnapshot FALSE = new BooleanValueSnapshot(false);

    public BooleanValueSnapshot(Boolean value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putBoolean(getValue());
    }
}
