package com.tyron.builder.util.internal;

import com.google.common.collect.Interner;
import com.google.common.collect.Maps;

import java.util.Map;

public class SimpleMapInterner implements Interner<String> {
    private final Map<String, String> internedStrings;

    private SimpleMapInterner(Map<String, String> interned) {
        this.internedStrings = interned;
    }

    public static SimpleMapInterner notThreadSafe() {
        return new SimpleMapInterner(Maps.<String, String>newHashMap());
    }

    public static SimpleMapInterner threadSafe() {
        return new SimpleMapInterner(Maps.<String, String>newConcurrentMap());
    }

    @Override
    @SuppressWarnings("ALL")
    public String intern(String sample) {
        if (sample == null) {
            return null;
        }
        String interned = internedStrings.get(sample);
        if (interned != null) {
            return interned;
        }
        internedStrings.put(sample, sample);
        return sample;
    }
}
