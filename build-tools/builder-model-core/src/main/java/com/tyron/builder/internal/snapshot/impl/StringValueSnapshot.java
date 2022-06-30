package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;

import java.nio.charset.StandardCharsets;

public class StringValueSnapshot extends AbstractIsolatableScalarValue<String> {
    public StringValueSnapshot(String value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(getValue(), StandardCharsets.UTF_8);
    }
}