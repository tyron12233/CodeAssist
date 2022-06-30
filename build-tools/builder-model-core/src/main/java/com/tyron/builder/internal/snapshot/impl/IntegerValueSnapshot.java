package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;

public class IntegerValueSnapshot extends AbstractIsolatableScalarValue<Integer> {
    public IntegerValueSnapshot(Integer value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putInt(getValue());
    }
}