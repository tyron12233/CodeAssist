package com.tyron.builder.api.internal;

import java.io.Serializable;

/**
 * A non-thread-safe type to hold a mutable boolean value.
 */
public final class MutableBoolean implements Serializable {
    private boolean value;

    public MutableBoolean() {
        this(false);
    }

    public MutableBoolean(boolean initialValue) {
        this.value = initialValue;
    }

    public void set(boolean value) {
        this.value = value;
    }

    public boolean get() {
        return value;
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}