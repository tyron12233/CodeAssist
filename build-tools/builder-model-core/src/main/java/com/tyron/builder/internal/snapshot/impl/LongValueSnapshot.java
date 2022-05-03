package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;

public class LongValueSnapshot extends AbstractIsolatableScalarValue<Long> {
    public LongValueSnapshot(Long value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putLong(getValue());
    }
}