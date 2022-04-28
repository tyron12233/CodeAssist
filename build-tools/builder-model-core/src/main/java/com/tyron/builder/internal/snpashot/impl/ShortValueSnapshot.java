package com.tyron.builder.internal.snpashot.impl;

import com.google.common.hash.Hasher;

public class ShortValueSnapshot extends AbstractIsolatableScalarValue<Short> {
    public ShortValueSnapshot(Short value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putInt(getValue());
    }
}