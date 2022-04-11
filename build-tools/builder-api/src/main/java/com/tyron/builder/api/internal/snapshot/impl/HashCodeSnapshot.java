package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

public class HashCodeSnapshot extends AbstractIsolatableScalarValue<HashCode> {
    public HashCodeSnapshot(HashCode value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putBytes(getValue().asBytes());
    }
}
