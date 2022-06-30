package com.tyron.builder.api.internal.cache;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

import org.jetbrains.annotations.Nullable;

public class StringInterner implements Interner<String> {
    private final Interner<String> interner;

    public StringInterner() {
        this.interner = Interners.newWeakInterner();
    }

    @Override
    public String intern(@Nullable String sample) {
        if (sample == null) {
            return null;
        }
        return interner.intern(sample);
    }
}