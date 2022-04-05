package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;

public class Actions {

    public static final Action<?> DO_NOTHING = __ -> {};

    public static <T> Action<T> doNothing() {
        //noinspection unchecked
        return (Action<T>) DO_NOTHING;
    }
}
